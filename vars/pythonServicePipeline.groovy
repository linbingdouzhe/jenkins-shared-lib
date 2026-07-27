def call(Map config) {
  def registry = config.registry ?: 'registry.registry.svc.cluster.local:5000'
  def gitBranch = config.gitBranch ?: 'main'
  def sonarTestInclusions = config.sonarTestInclusions ?: 'test_*.py'
  def sonarExclusions = config.sonarExclusions ?: 'templates/**'
  def manifestPath = config.manifestPath ?: ''
  def manifestRepoSshUrl = config.manifestRepoSshUrl ?: ''
  // Kaniko pushes from inside a pod, so the cluster-DNS registry address (config.registry)
  // resolves fine there. Deployed manifests are pulled by kubelet on the node itself, which
  // uses the node's own resolver and can't see *.svc.cluster.local -- so the image reference
  // written into the manifest must use a node-resolvable address instead (e.g. a NodePort).
  def deployRegistry = config.deployRegistry ?: registry

  pipeline {
    agent {
      kubernetes {
        yaml '''
  apiVersion: v1
  kind: Pod
  spec:
    containers:
    - name: kaniko
      image: gcr.io/kaniko-project/executor:debug
      command:
      - sleep
      args:
      - 9999999
    - name: python
      image: python:3.11-slim
      command:
      - sleep
      args:
      - 9999999
    - name: git
      image: alpine/git:latest
      command:
      - sleep
      args:
      - 9999999
      volumeMounts:
      - name: jenkins-cd-key
        mountPath: /etc/jenkins-cd-key
        readOnly: true
    volumes:
    - name: jenkins-cd-key
      secret:
        secretName: github-deploy-key
        defaultMode: 0400
  '''
      }
    }
    environment {
      REGISTRY        = "${registry}"
      DEPLOY_REGISTRY = "${deployRegistry}"
      IMAGE           = "${config.serviceName}"
      SERVICE_PATH    = "${config.servicePath}"
      MANIFEST_PATH   = "${manifestPath}"
      MANIFEST_REPO_SSH = "${manifestRepoSshUrl}"
    }
    stages {
      stage('Checkout') {
        steps {
          git url: config.gitUrl, branch: gitBranch
        }
      }
      stage('Unit Test') {
        steps {
          container('python') {
            dir(env.SERVICE_PATH) {
              sh '''
                pip install --no-cache-dir -r requirements.txt pytest pytest-cov
                pytest \
                  --junitxml=test-results.xml \
                  --cov=. \
                  --cov-report=xml:coverage.xml
              '''
            }
          }
        }
        post {
          always {
            junit "${env.SERVICE_PATH}/test-results.xml"
          }
        }
      }
      stage('SonarQube Analysis') {
        steps {
          dir(env.SERVICE_PATH) {
            script {
              def scannerHome = tool 'SonarScanner'
              withSonarQubeEnv() {
                sh """
                  ${scannerHome}/bin/sonar-scanner \
                    -Dsonar.projectKey=${config.serviceName} \
                    -Dsonar.sources=. \
                    -Dsonar.tests=. \
                    -Dsonar.test.inclusions=${sonarTestInclusions} \
                    -Dsonar.exclusions=${sonarExclusions} \
                    -Dsonar.python.coverage.reportPaths=coverage.xml
                """
              }
            }
          }
        }
      }
      stage('Quality Gate') {
        steps {
          dir(env.SERVICE_PATH) {
            timeout(time: 5, unit: 'MINUTES') {
              waitForQualityGate abortPipeline: true
            }
          }
        }
      }
      stage('Build & Push') {
        steps {
          container('kaniko') {
            sh '''
              /kaniko/executor \
                --context=`pwd`/$SERVICE_PATH \
                --dockerfile=Dockerfile \
                --destination=$REGISTRY/$IMAGE:$BUILD_NUMBER \
                --insecure
            '''
          }
        }
      }
      stage('Update Deploy Manifest') {
        when {
          expression { return manifestPath && manifestRepoSshUrl }
        }
        steps {
          container('git') {
            sh '''
              mkdir -p ~/.ssh
              cp /etc/jenkins-cd-key/id_ed25519 ~/.ssh/id_ed25519
              chmod 600 ~/.ssh/id_ed25519
              export GIT_SSH_COMMAND="ssh -o StrictHostKeyChecking=no -i ~/.ssh/id_ed25519"
              git config user.email "jenkins-ci@local"
              git config user.name "Jenkins CI"
              sed -i "s#^\\( *image: \\).*#\\1$DEPLOY_REGISTRY/$IMAGE:$BUILD_NUMBER#" "$MANIFEST_PATH"
              git add "$MANIFEST_PATH"
              git diff --cached --quiet && echo "no manifest change" || git commit -m "ci: bump $IMAGE image to $BUILD_NUMBER"
              git push "$MANIFEST_REPO_SSH" HEAD:main
            '''
          }
        }
      }
    }
  }
}
