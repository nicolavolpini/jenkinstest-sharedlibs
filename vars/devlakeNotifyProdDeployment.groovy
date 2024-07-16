import groovy.json.JsonSlurperClassic
import groovy.json.JsonOutput

/** This script is meant to be run by Jenkins. It collects infos about a prod deployment and
* notifies DevLake by calling a webhook specific to the team.
* In essence: based on the service being deployed, the script grabs the PR commit SHA and repo from GitHub,
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
    debug = true

    // Collect the Repo corresponding to the app/service
    // repo = getRepoName(args.appname, args.version, args.artifactorybearer)
    repo = 'dummy-svc'

    if (debug) { println("Running main call with params: currentBuild: ${currentBuild}, repo: ${repo}, version/tag: ${args.version}") }

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

                if (debug) { println("Teams associated to repo ${repo} excluding Plugsurfing: ${teams}")}

                // Run the notifier block for every team the repo is associated to in GitHub

                // Run only if at least one team is associated to the repo
                if (teams.size() > 0) {
                    if (debug) { println('Running teams loop.') }
                    // Collect the release SHA from GitHub
                    commitsha = getCommitSha(repo, args.version, args.ghbearer)
                    if (commitsha) {
                        // Generate the DevLake Payload
                        payload = generatePayload(repo, commitsha)
                        for (team in teams) {
                            // Collect the webhook path programmatically from DevLake
                            webhook = getWebhook(team, args.dlbearer)
                            if (webhook) {
                                if (debug){ println("Call webhook for team ${team}")}

                                notifyDeployment(payload, webhook, args.dlbearer)
                            }
                            else {
                                println("Team ${team} has no corresponding webhook in DevLake, or unable to reach DevLake.")
                            }
                        }
                    }
                    else {
                        println('No valid commit sha found. Exiting')
                    }
                }
                else {
                    println('Not executing notification script. Probably no teams associated to repo.')
                }
            }
            else {
                println("ERROR: main function returned code: ${getRC}, message: ${getMessage}. Ensure you are querying the right repo name in GitHub.")
            }
        }
        catch (Exception e) {
            println("ERROR: GET exception for main function: ${e}")
        }
    }
    else {
        println("ERROR: unable to get repo property from Artifactory. Missing 'repo' property in artifact or other error.")
    }
    get = null
}
/**
* Obtain the github commit sha corresponding to the release tag
*/
def getCommitSha(repo, version, ghbearer) {
    // encode special chars such as '@' so the GH API does not freak out
    encodedVersion = java.net.URLEncoder.encode(version, 'UTF-8')

    if (debug) { println("Encoded tag/version: ${encodedVersion}") }

    try {
        def get = new URL("https://api.github.com/repos/plugsurfing/${repo}/git/ref/tags/${encodedVersion}").openConnection()
        get.requestMethod = 'GET'
        get.setRequestProperty('Content-Type', 'application/json')
        get.setRequestProperty('Authorization', 'Bearer ' + ghbearer)
        get.setRequestProperty('X-GitHub-Api-Version', '2022-11-28')
        getRC = get.getResponseCode()
        getResponseMessage = get.getResponseMessage()
        if (debug) { println("Requesting SHA for repo: ${repo}, version: ${version}") }

        if (getRC == (200)) {
            response = get.inputStream.getText()
            shaJson = new JsonSlurperClassic().parseText(response)
            sha = shaJson.object.sha
            if (debug) { println("Returned SHA value is ${sha}") }
            return sha
        }
        else {
            println("ERROR: No valid commit sha. Returned response code: ${getRC}, message ${getResponseMessage}. Check whether the version/tag exists for that repo/branch.")
        }
    }
    catch (Exception e) {
        println("ERROR: GET exception: ${e}")
    }
    get = null
}

/**
* Obtain the GitHub repo name from Artifactory
*/
def getRepoName(appname, version, artifactorybearer) {
    try {
        def get = new URL("https://plugsurfing.jfrog.io/artifactory/api/storage/ps-generic/${appname}/${version}?properties=repo").openConnection()
        get.requestMethod = 'GET'
        get.setRequestProperty('X-JFrog-Art-Api', artifactorybearer)
        getRC = get.getResponseCode()
        getResponseMessage = get.getResponseMessage()
        if (debug) { println("Requesting sha for appname: ${appname}, version: ${version}") }

        if (getRC == (200)) {
            response = get.inputStream.getText()
            artifactoryJson = new JsonSlurperClassic().parseText(response)
            repo = artifactoryJson.properties.repo[0]
            if (debug) { println("Returned repo value is ${repo}") }
            return repo
        }
        else {
            println("ERROR: No valid repo returned. Returned response code: ${getRC}, message ${getResponseMessage}. Check whether the repo property exists for the artifact in Artifactory.")
        }
    }
    catch (Exception e) {
        println("ERROR: GET exception: ${e}")
    }
    get = null
}

/**
* Obtain the correct webhook from the DevLake API based on the GitHub team name.
* It expects the DevLake webhook to be named EXACTLY `<github-team-slug>-webhook`.
*/
def getWebhook(teamName, dlbearer) {
    webhook = teamName + '-webhook'
    if (debug) {
        println("Requesting webhook path from DevLake. Webhook name requested: ${webhook}")
    }

    try {
        def get = new URL('https://devlake-configui.central.plugsurfing-infra.com/api/rest/plugins/webhook/connections').openConnection()
        get.requestMethod = 'GET'
        get.setRequestProperty('Content-Type', 'application/json')
        get.setRequestProperty('Authorization', 'Bearer ' + dlbearer)
        getRC = get.getResponseCode()


        if (getRC == (200)) {
            response = get.inputStream.getText()
            webhookJson = new JsonSlurperClassic().parseText(response)
            match = webhookJson.find { element ->
                element.name == webhook
            }
            if (match) {
                endpoint = match.postPipelineDeployTaskEndpoint
                if (debug) {
                    println("Webhook path returned: ${endpoint}")
                }
                return endpoint
            }
            else {
                if (debug) {
                    println("ERROR: no valid webhook path returned.")
                }
                return null
            }
        }
        else {
            println("ERROR: getWebhook function returned response code: ${getRC}")
        }
    }
    catch (Exception e) {
        println("ERROR: GET exception: ${e}")
    }
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
    println("Notifying DevLake.")

    if (debug) {
        println("Curl command: ${devlakePublish}")
    }
}
