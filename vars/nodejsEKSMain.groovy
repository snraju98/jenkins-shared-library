def call(Map configMap) {
    pipeline {
        agent { 
            node { 
                label 'ROBOSHOP' 
            } 
        }
        environment {
            def appVersion = ""
            acc_id = "786020472029"
            project = configMap.get("project")
            component = configMap.get("component")
            org = "snraju98"
        }
        options {
            disableConcurrentBuilds()
            timeout(time: 15, unit: 'MINUTES')
        }
        stages {
            stage('Read version') {
                steps {
                    script {
                        def packageJson = readJSON file: 'package.json'
                        appVersion = packageJson.version
                        echo "The application version is: ${appVersion}"
                    }
                }
            }
            stage('Dev Deploy') {
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh """
                                    aws eks update-kubeconfig --region us-east-1 --name roboshop-dev
                                    cd helm
                                    helm upgrade --install ${component} -f values-dev.yaml -n roboshop-dev \\
                                    --set deployment.imageVersion=${appVersion} \\
                                    --wait --timeout 5m .

                                    kubectl rollout status deployment/${component} -n roboshop-dev --timeout=2m
                                """
                            }
                            utils.updateCommitStatus("success", "dev deploy succcess", "dev-deploy")
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus("failure", "dev deploy failed", "dev-deploy")
                            throw e
                        }
                    }
                }
            }
            stage('Dev API Tests') {
                steps {
                    script {
                        try {
                            build job: 'ROBOSHOP/catalogue-api-tests', parameters: [
                                string(name: 'NAMESPACE', value: 'roboshop-dev'),
                                string(name: 'COMMIT_ID', value: env.GIT_COMMIT)
                            ], wait: true, propagate: true
                            utils.updateCommitStatus('success', 'catalogue-api-tests passed', 'api-tests')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'catalogue-api-tests failed', 'api-tests')
                            throw e
                        }
                    }
                }
            }
        }
        post { 
            always { 
                echo 'I will always say Hello again!'
            }
            success {
                slackSend channel: '#jenkins-alerts-90s',
                          color: 'good',
                          message: "SUCCESS: Job '${env.JOB_NAME}' (${env.BUILD_NUMBER}) (<${env.BUILD_URL}|View Build>)" 
            }
            failure { 
                slackSend channel: '#jenkins-alerts-90s',
                          color: 'danger',
                          message: "Failure: Job '${env.JOB_NAME}' (${env.BUILD_NUMBER}) (<${env.BUILD_URL}|View Build>)"
            }
        }
    }
}