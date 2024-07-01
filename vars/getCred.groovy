def call() {
    withCredentials([string(credentialsId: 'github-token', variable: 'token')]) {
        echo "token is $token"
    }
}
