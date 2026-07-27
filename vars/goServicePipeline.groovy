def call(Map config) {
  def registry = config.registry ?: 'registry.registry.svc.cluster.local:5000'
  def gitBranch = config.gitBranch ?: 'main'
  def sonarTestInclusions = config.sonarTestInclusions ?: '**/*_test.go'

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
    - name: golang
      image: golang:1.25
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
          container('golang') {
            dir(env.SERVICE_PATH) {
              sh '''
                go install github.com/jstemmer/go-junit-report@latest
                go test ./... -v -coverprofile=coverage.out 2>&1 | tee test-output.txt
                $(go env GOPATH)/bin/go-junit-report < test-output.txt > test-results.xml
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
                    -Dsonar.go.coverage.reportPaths=coverage.out
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
