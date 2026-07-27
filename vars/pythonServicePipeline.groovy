def call(Map config) {
  def registry = config.registry ?: 'registry.registry.svc.cluster.local:5000'
  def gitBranch = config.gitBranch ?: 'main'
  def sonarTestInclusions = config.sonarTestInclusions ?: 'test_*.py'
  def sonarExclusions = config.sonarExclusions ?: 'templates/**'

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
  '''
      }
    }
    environment {
      REGISTRY     = "${registry}"
      IMAGE        = "${config.serviceName}"
      SERVICE_PATH = "${config.servicePath}"
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
    }
  }
}
