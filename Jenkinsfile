#!/usr/bin/env groovy

static def accountId(String environment) {
    def account_info = [
            integration : ["account_id": "063874132475"],
            staging     : ["account_id": "063874132475"],
            qa          : ["account_id": "063874132475"],
            externaltest: ["account_id": "415042754718"],
            production  : ["account_id": "415042754718"],
            development : ["account_id": "063874132475"]
    ]

    account_info[environment]["account_id"]

}

static def generateAWSCredentials(String environment) {
    """set +x
    |SESSIONID=\$(date +"%s")
    |AWS_CREDENTIALS=\$(aws sts assume-role --role-arn arn:aws:iam::${accountId(environment)}:role/jenkins-management-role --role-session-name \$SESSIONID --query '[Credentials.AccessKeyId,Credentials.SecretAccessKey,Credentials.SessionToken]' --output text)
    |export AWS_ACCESS_KEY_ID=\$(echo \$AWS_CREDENTIALS | awk '{print \$1}')
    |export AWS_SECRET_ACCESS_KEY=\$(echo \$AWS_CREDENTIALS | awk '{print \$2}')
    |export AWS_SESSION_TOKEN=\$(echo \$AWS_CREDENTIALS | awk '{print \$3}')
    |set -x"""
}

node() {

    def environment = "${env.JOB_BASE_NAME}"

    def region = "eu-west-2"

    def upscanUploadProxy = "upscan-upload-proxy"

    def gitCommitSha = checkout(scm).GIT_COMMIT

    def ecrLogin = {
        sh """${generateAWSCredentials(environment)}
                         |\$(aws ecr get-login --no-include-email --region eu-west-2)""".stripMargin()
    }

    def tagDockerImage = { resourceType, version ->
        sh """docker tag ${resourceType}:${gitCommitSha} ${accountId(environment)}.dkr.ecr.${region}.amazonaws.com/${
            resourceType
        }-${environment}:${version}""".stripMargin()
    }

    def pushDockerImage = { resourceType, version ->
        sh """docker push ${accountId(environment)}.dkr.ecr.${region}.amazonaws.com/${resourceType}-${
            environment
        }:${version}""".stripMargin()
    }

    def deployLatestVersion = { resourceType ->
        sh """${generateAWSCredentials(environment)}
                          |aws ecs update-service --force-new-deployment --cluster ${resourceType}-${
            environment
        }-cluster --service ${resourceType}-${environment}""".stripMargin()

    }

    try {

        stage('Compile') {
            ansiColor('xterm') {
                sh "sbt compile"
            }
        }

        stage('Test') {
            ansiColor('xterm') {
                sh "sbt test"
            }
        }

        stage('Create Docker Image') {
            ansiColor('xterm') {
                sh "sbt docker:publishLocal"
            }
        }

        stage('Publish to ECR') {
            ecrLogin()
            tagDockerImage(upscanUploadProxy, "latest")
            pushDockerImage(upscanUploadProxy, "latest")

            tagDockerImage(upscanUploadProxy, gitCommitSha)
            pushDockerImage(upscanUploadProxy, gitCommitSha)
        }

        stage('Deploy') {
            deployLatestVersion(upscanUploadProxy)
        }
    }
    finally {
        //delete all images
        sh "docker rmi -f \$(docker images -q)"

        echo 'Clean workspace'
        cleanWs()
    }

}
