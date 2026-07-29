def call(Map config) {
  def registry = config.registry ?: 'registry.registry.svc.cluster.local:5000'
  def gitBranch = config.gitBranch ?: 'main'
  def sonarSources = config.sonarSources ?: 'src/main'
  def sonarTests = config.sonarTests ?: 'src/test'

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
    - name: gradle
      image: gradle:8-jdk21
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
          container('gradle') {
            dir(env.SERVICE_PATH) {
              sh '''
                gradle test jacocoTestReport --no-daemon
              '''
            }
          }
        }
        post {
          always {
            junit "${env.SERVICE_PATH}/build/test-results/test/*.xml"
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
                    -Dsonar.sources=${sonarSources} \
                    -Dsonar.tests=${sonarTests} \
                    -Dsonar.java.binaries=build/classes \
                    -Dsonar.coverage.jacoco.xmlReportPaths=build/reports/jacoco/test/jacocoTestReport.xml \
                    -Dsonar.junit.reportPaths=build/test-results/test
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
