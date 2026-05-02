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
            // Image name is passed dynamically from each service's Jenkinsfile
            IMAGE_REPO_NAME = "${config.imageName}" 
            IMAGE_TAG = "${env.BUILD_NUMBER}"
            // Application port is passed dynamically to avoid conflicts between services
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
                    // Dynamically updates the server port in application.properties
                    sh "echo server.port=${APP_PORT} >> src/main/resources/application.properties"
                }
            }
            
            stage('compile') {
                steps {
                    sh 'mvn clean compile'
                }
            }
            
            stage('test') {
                steps {
                    sh 'mvn test'
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
                        // Builds the Docker image and tags it with Build Number and Latest
                        sh "docker build -t ${REPOSITORY_URI}:${IMAGE_TAG} ."
                        sh "docker tag ${REPOSITORY_URI}:${IMAGE_TAG} ${REPOSITORY_URI}:latest"
                    }
                }
            }
            
            stage('Push to AWS ECR') {
                steps {
                    withCredentials([usernamePassword(credentialsId: 'aws-credentials-id', 
                                                     passwordVariable: 'AWS_SECRET_ACCESS_KEY', 
                                                     usernameVariable: 'AWS_ACCESS_KEY_ID')]) {
                        sh """
                        # Authenticate Docker with AWS ECR and push images
                        aws ecr get-login-password --region ${AWS_DEFAULT_REGION} | docker login --username AWS --password-stdin ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_DEFAULT_REGION}.amazonaws.com
                        docker push ${REPOSITORY_URI}:${IMAGE_TAG}
                        docker push ${REPOSITORY_URI}:latest
                        """
                    }
                }
            }
            
            stage('Deploy') {
                steps {
                    script {
                        // Removes the old container if it exists and runs the new one
                        sh "docker rm -f ${IMAGE_REPO_NAME} || true"
                        sh "docker run -d -p ${APP_PORT}:${APP_PORT} --name ${IMAGE_REPO_NAME} ${REPOSITORY_URI}:latest"
                    }
                }
            }
        }    
    }
}
