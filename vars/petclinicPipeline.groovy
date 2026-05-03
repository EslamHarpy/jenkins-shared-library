def call(Map config = [:]) {
    pipeline {
        agent any 
        
        tools {
            maven 'maven-3.9.15'
            jdk 'jdk-17'
        }
        
        environment {
            AWS_ACCOUNT_ID = '053274260339' 
            AWS_DEFAULT_REGION = 'us-east-1' 
            // Fixed ECR name to use one repository for all services
            IMAGE_REPO_NAME = 'my-spring-petclinic' 
            // Unique service-specific tag (e.g., service-a-15)
            SERVICE_TAG = "${config.imageName}-${env.BUILD_NUMBER}"
            // Unique service-latest tag (e.g., service-a-latest)
            SERVICE_LATEST = "${config.imageName}-latest"
            
            APP_PORT = "${config.appPort}"
            REPOSITORY_URI = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_DEFAULT_REGION}.amazonaws.com/${IMAGE_REPO_NAME}"
        }
        
        stages {
            stage('first stage') {
                steps {
                    sh 'date'
                    sh 'echo hello from iti'
                    sh 'whoami'
                    sh 'pwd'
                }
            }
            
            stage('clone') {
                steps {
                    // Automatically clones the repository that invoked the library
                    checkout scm
                }
            }
            
            stage('change config') {
                steps {
                    // Overwrite application.properties with the dynamic port
                    sh "echo 'server.port=${APP_PORT}' > src/main/resources/application.properties"
                }
            }
            
            stage('compile') {
                steps {
                    sh 'mvn clean compile'
                }
            }
            
            stage('test') {
                steps {
                    sh 'mvn test  '
                }
            }
            
            stage('package') {
                steps {
                    // Packages the application into a JAR file, skipping tests to save time
                    sh 'mvn package -DskipTests'
                }
            }
            
            stage('Docker Build & Tag') {
                steps {
                    script {
                        // Builds image with a unique tag per service
                        sh "docker build -t ${REPOSITORY_URI}:${SERVICE_TAG} ."
                        sh "docker tag ${REPOSITORY_URI}:${SERVICE_TAG} ${REPOSITORY_URI}:${SERVICE_LATEST}"
                    }
                }
            }
            
            stage('Push to AWS ECR') {
                steps {
                    withCredentials([usernamePassword(credentialsId: 'aws-credentials-id', 
                                                     passwordVariable: 'AWS_SECRET_ACCESS_KEY', 
                                                     usernameVariable: 'AWS_ACCESS_KEY_ID')]) {
                        sh """
                        # Authenticate Docker with AWS ECR
                        aws ecr get-login-password --region ${AWS_DEFAULT_REGION} | docker login --username AWS --password-stdin ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_DEFAULT_REGION}.amazonaws.com
                        # Push the specific build tag and the service-latest tag
                        docker push ${REPOSITORY_URI}:${SERVICE_TAG}
                        docker push ${REPOSITORY_URI}:${SERVICE_LATEST}
                        """
                    }
                }
            }
            
            stage('Deploy') {
                steps {
                    script {
                        // Removes the old container based on the service name (config.imageName)
                        sh "docker rm -f ${config.imageName} || true"
                        // Runs the container using the service-specific latest tag and overrides the port via arguments
                        sh "docker run -d -p ${APP_PORT}:${APP_PORT} --name ${config.imageName} ${REPOSITORY_URI}:${SERVICE_LATEST} --server.port=${APP_PORT}"
                    }
                }
            }
        }    
    }
}
