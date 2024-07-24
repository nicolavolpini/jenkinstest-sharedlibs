import groovy.json.JsonSlurperClassic
import groovy.json.JsonOutput

/** This script is meant to be run by Jenkins. It collects infos about a prod deployment and
* notifies DevLake by calling a webhook specific to the team.
* In detail: based on the service being deployed, the script fetches the PR commit SHA and repo from GitHub,
* finds the correct DevLake webhook and calls the webhook with the deployment infos.
*
* Expected parameters:
* - application name
* - application tag/version
* - startTimeInMillis (epoch time when the deployment started, including milliseconds. Example 1715161920000)
* - duration (the time it took the deployment to prod to complete, including milliseconds)
**/

/**
* Main function. Aggregates the sub-functions and runs them based on certain conditions.
*/
def call(Map args) {
    debug = args.debug ?: false

    // Collect the Repo corresponding to the app/service
    repo = getRepoName(args.appname, args.version, args.afbearer)

    if (debug) { println("DEVLAKE DEBUG: Running the main function with the following parameters: currentBuild: ${currentBuild}, repo: ${repo}, version/tag: ${args.version}") }

    // I am really sorry for this nested if. I shall find a cleaner alternative asap.
    if (repo) {
        try {
            get = new URL('https://api.github.com/repos/plugsurfing/' + repo + '/teams').openConnection()
            get.requestMethod = 'GET'
            get.setRequestProperty('Content-Type', 'application/json')
            get.setRequestProperty('Authorization', 'Bearer ' + args.ghbearer)
            get.setRequestProperty('X-GitHub-Api-Version', '2022-11-28')
            getRC = get.getResponseCode()
            getMessage = get.getResponseMessage()

            if (getRC == (200)) {
                response = get.inputStream.getText()
                mainFunctionJson = new JsonSlurperClassic().parseText(response)
                teams = mainFunctionJson.slug

                // skip "plugsurfing" since it is a common team with no corresponding DevLake project.
                teams -= 'plugsurfing'

                if (debug) { println("DEVLAKE DEBUG: Teams associated to repo ${repo} excluding 'plugsurfing': ${teams}")}

                // Run the notifier block for every team the repo is associated to in GitHub

                // Run only if at least one team is associated to the repo
                if (teams.size() > 0) {
                    if (debug) { println('DEVLAKE DEBUG: Running main script for each team') }
                    // Collect the release SHA from GitHub
                    commitsha = getCommitSha(repo, args.version, args.ghbearer)
                    if (commitsha) {
                        // Generate the DevLake Payload
                        payload = generatePayload(repo, commitsha)
                        for (team in teams) {
                            // Collect the webhook path programmatically from DevLake
                            webhook = getWebhook(team, args.dlbearer)
                            if (webhook) {
                                if (debug){ println("DEVLAKE DEBUG: Call webhook for team ${team}")}

                                notifyDeployment(payload, webhook, args.dlbearer)
                            }
                            else {
                                println("DEVLAKE DEBUG: Not executing DevLake deployment notification. Reason: Team ${team} has no corresponding webhook in DevLake, or unable to reach DevLake.")
                            }
                        }
                    }
                    else {
                        println('DEVLAKE DEBUG: Not executing DevLake deployment notification. Reason: no valid commit sha found. ')
                    }
                }
                else {
                    println('DEVLAKE DEBUG: Not executing DevLake deployment notification. Reason: probably no team is associated to the repo in GitHub.')
                }
            }
            else {
                println("DEVLAKE DEBUG: Not executing DevLake deployment notification. Reason: main function returned code: ${getRC}, message: ${getMessage}. Ensure you are able to reach the GitHub API and/or you are querying the right repo name.")
            }
        }
        catch (Exception e) {
            println("DEVLAKE DEBUG: Not executing DevLake deployment notification. Reason: GET exception for main function: ${e}")
        }
    }
    else {
        println("DEVLAKE DEBUG: Not executing DevLake deployment notification. Reason: unable to get repo property from Artifactory. Missing 'repo' property in artifact or other error.")
    }

    // fixes the serialization issues in Jenkins:
    // https://www.jenkins.io/doc/book/pipeline/pipeline-best-practices/#avoiding-notserializableexception
    get = null
}
/**
* Obtain the github commit sha corresponding to the release tag
*/
def getCommitSha(repo, version, ghbearer) {
    // encode special chars such as '@' so the GH API does not freak out
    encodedVersion = java.net.URLEncoder.encode(version, 'UTF-8')

    if (debug) { println("DEVLAKE DEBUG: Encoded tag/version: ${encodedVersion}") }

    try {
        url = "https://api.github.com/repos/plugsurfing/${repo}/git/ref/tags/${encodedVersion}"
        def get = new URL(url).openConnection()
        get.requestMethod = 'GET'
        get.setRequestProperty('Content-Type', 'application/json')
        get.setRequestProperty('Authorization', 'Bearer ' + ghbearer)
        get.setRequestProperty('X-GitHub-Api-Version', '2022-11-28')
        getRC = get.getResponseCode()
        getResponseMessage = get.getResponseMessage()
        if (debug) { println("DEVLAKE DEBUG: Requesting SHA for repo: ${repo}, version: ${version}. GitHub url: ${url}") }

        if (getRC == (200)) {
            response = get.inputStream.getText()
            shaJson = new JsonSlurperClassic().parseText(response)
            sha = shaJson.object.sha
            if (debug) { println("DEVLAKE DEBUG: Returned SHA value is ${sha}") }
            return sha
        }
        else {
            println("DEVLAKE DEBUG: ERROR: No valid commit sha. Returned response code: ${getRC}, message ${getResponseMessage}. Check whether the version/tag exists for that repo/branch.")
        }
    }
    catch (Exception e) {
        println("DEVLAKE DEBUG: ERROR: GET exception: ${e}")
    }
    // fixes the serialization issues in Jenkins:
    // https://www.jenkins.io/doc/book/pipeline/pipeline-best-practices/#avoiding-notserializableexception
    get = null
}

/**
* Obtain the GitHub repo name from Artifactory
*/
def getRepoName(appname, version, artifactorybearer) {
    try {
        url = "https://plugsurfing.jfrog.io/artifactory/api/storage/ps-generic/terraform/${appname}/${version}?properties=repo"
        def get = new URL(url).openConnection()
        get.requestMethod = 'GET'
        get.setRequestProperty('X-JFrog-Art-Api', artifactorybearer)
        getRC = get.getResponseCode()
        getResponseMessage = get.getResponseMessage()
        if (debug) { println("DEVLAKE DEBUG: Requesting repo info for appname: ${appname}, version: ${version}. Artifactory url: ${url}") }

        if (getRC == (200)) {
            response = get.inputStream.getText()
            artifactoryJson = new JsonSlurperClassic().parseText(response)
            repo = artifactoryJson.properties.repo[0]
            if (debug) { println("DEVLAKE DEBUG: Returned repo value from Artifactory: ${repo}") }
            return repo
        }
        else {
            println("DEVLAKE DEBUG: ERROR: No valid repo returned. Returned response code: ${getRC}, message ${getResponseMessage}. Check whether the repo property exists for the artifact in Artifactory or whether you can connect to Artifactory.")
        }
    }
    catch (Exception e) {
        println("DEVLAKE DEBUG: ERROR: GET exception: ${e}")
    }
    // fixes the serialization issues in Jenkins:
    // https://www.jenkins.io/doc/book/pipeline/pipeline-best-practices/#avoiding-notserializableexception
    get = null
}

/**
* Obtain the correct webhook from the DevLake API based on the GitHub team name.
* It expects the DevLake webhook to be named EXACTLY `<github-team-slug>-webhook`.
*/
def getWebhook(teamName, dlbearer) {
    webhook = teamName + '-webhook'
    if (debug) {
        println("DEVLAKE DEBUG: Requesting webhook path from DevLake. Webhook name requested: ${webhook}")
    }

    // get all webhooks configured in DevLake
    try {
        def get = new URL('https://devlake-configui.central.plugsurfing-infra.com/api/rest/plugins/webhook/connections').openConnection()
        get.requestMethod = 'GET'
        get.setRequestProperty('Content-Type', 'application/json')
        get.setRequestProperty('Authorization', 'Bearer ' + dlbearer)
        getRC = get.getResponseCode()

        // Find out which webhook matches the team in github
        if (getRC == (200)) {
            response = get.inputStream.getText()
            webhookJson = new JsonSlurperClassic().parseText(response)
            match = webhookJson.find { element ->
                element.name == webhook
            }
            if (match) {
                endpoint = match.postPipelineDeployTaskEndpoint
                if (debug) {
                    println("DEVLAKE DEBUG: Webhook path returned: ${endpoint}")
                }
                return endpoint
            }
            else {
                if (debug) {
                    println("DEVLAKE DEBUG: ERROR: no valid webhook path returned.")
                }
                return null
            }
        }
        else {
            println("DEVLAKE DEBUG: ERROR: getWebhook function returned response code: ${getRC}")
        }
    }
    catch (Exception e) {
        println("DEVLAKE DEBUG: ERROR: GET exception: ${e}")
    }
    // fixes the serialization issues in Jenkins:
    // https://www.jenkins.io/doc/book/pipeline/pipeline-best-practices/#avoiding-notserializableexception
    get = null
}
/**
* Generate the payload to be passed to the Devlake webhook
*/
def generatePayload(repo, commitsha) {

    // Process deployment data
    def buildEndTime = currentBuild.startTimeInMillis + currentBuild.duration
    def buildEndTimeClean = new Date(buildEndTime).format('yyyy-MM-dd HH:mm:ss')
    def buildStartTimeClean = new Date(currentBuild.startTimeInMillis).format('yyyy-MM-dd HH:mm:ss')
    def buildResult = currentBuild.currentResult

    jsonPayload = JsonOutput.toJson([deploymentCommits: [
        [
            startedDate: "${buildStartTimeClean}",
            finishedDate:"${buildEndTimeClean}",
            commitSha: "${commitsha}",
            result: "${buildResult}",
            repoUrl: "https://github.com/plugsurfing/${repo}"
        ]
    ],
    id: "${commitsha}",
    result: "${buildResult}",
    startedDate: "${buildStartTimeClean}",
    finishedDate:"${buildEndTimeClean}"
    ])

    jsonPretty = JsonOutput.prettyPrint(jsonPayload)
    return jsonPretty
}

/**
* Call the DevLake deployment webhook
*/
def notifyDeployment(payload, webhook, dlbearer) {
    def devlakePublish = """
            curl https://devlake-configui.central.plugsurfing-infra.com/api${webhook} -X 'POST' -H 'Authorization: Bearer
            <hidden>' -d 
            '${payload}'
        """
    println("DEVLAKE: Notifying DevLake ( *** DRYRUN *** )")

    if (debug) {
        println("DEVLAKE DEBUG: Curl command ( *** DRYRUN *** ): ${devlakePublish}")
    }
}
/**
* Obtain the correct webhook from the DevLake API based on the GitHub team name.
* It expects the DevLake webhook to be named EXACTLY `<github-team-slug>-webhook`.
*/
def notifyDeploymentx(payload, webhook, dlbearer) {
    if (debug) {
        println("DEVLAKE DEBUG: Running notification function with following webhook: ${webhook}")
    }
    try {
        notifyurl = "https://devlake-configui.central.plugsurfing-infra.com/api${webhook}"
        post = new URL(notifyurl).openConnection()
        post.setDoOutput(true)
        post.requestMethod = 'POST'
        post.setRequestProperty('Content-Type', 'application/json')
        post.setRequestProperty('Authorization', 'Bearer ' + dlbearer)
        post.getOutputStream().write(payload.getBytes("UTF-8"));
        postRC = post.getResponseCode()

        // Find out which webhook matches the team in github
        if (postRC == (200)) {
            println("DEVLAKE DEBUG: Notifying devlake. URL: ${notifyurl}, Payload: ${payload}")
            println("Successfully notified DevLake.")
        }
        else {
            println("DEVLAKE DEBUG: ERROR: getWebhook function returned response code: ${getRC}")
        }
    }
    catch (Exception e) {
        println("DEVLAKE DEBUG: ERROR: exception: ${e}")
    }
    // fixes the serialization issues in Jenkins:
    // https://www.jenkins.io/doc/book/pipeline/pipeline-best-practices/#avoiding-notserializableexception
    post = null
}