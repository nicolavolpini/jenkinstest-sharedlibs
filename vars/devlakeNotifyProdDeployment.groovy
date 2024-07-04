import groovy.json.JsonSlurperClassic
import groovy.json.JsonOutput

// TODO: pass bearer securely

/** This script receives information about a prod deployment and
* sends it to DevLake by calling the team-specific webhook.
*
* Expected parameters:
* - Team (as specified in DevLake. Example "Revenue", "CPM", etc.)
* - repo (in the format "plugsurfing/<reponame>")
* - commit sha (in the SHA1 format)
* - startTimeInMillis (epoch time when the deployment started, including milliseconds. Example 1715161920000)
* - duration (the time it took the deployment to prod to complete, including milliseconds)
**/
// START vars for local testing
// def config = [
//     repo: 'plugsurfing/cdm-api',
// ]

// END vars for local testing

/**
* Main function. Based on the github repo, get the teams owning it and trigger a deployment notification to DevLake.
*/
def call(Map args) {
    // args: repo, tag, ghbearer, dlbearer
    if(debug)
{ 
    println("- DEBUG: Running main call with: currentBuild: ${currentBuild}, repo: ${args.repo}, version/tag: ${args.version}")
 }
    
    try {
        def get = new URL('https://api.github.com/repos/plugsurfing/' + args.repo + '/teams').openConnection()
        get.requestMethod = 'GET'
        get.setRequestProperty('Content-Type', 'application/json')
        get.setRequestProperty('Authorization', 'Bearer ' + args.ghbearer)
        get.setRequestProperty('X-GitHub-Api-Version', '2022-11-28')
        getRC = get.getResponseCode()

        if (getRC == (200)) {
            response = get.inputStream.getText()
            mainFunctionJson = new JsonSlurperClassic().parseText(response)
            teams = mainFunctionJson.slug
            
            // Run the main block for every team the repo belongs to in GitHub
            for (team in teams) {
                // Since "Plugsurfing" is a common team with no corresponding DevLake project, skip it.
                if (team != 'plugsurfing') {
                    // Collect the webhook path programmatically from DevLake
                    webhook = getWebhook(team, args.dlbearer)
                    if (webhook) {
                        if (debug) { println("- DEBUG: Call webhook for team ${team}")}

                        commitsha = commitSha(args.repo, args.version, args.ghbearer)
                        if (commitsha) {
                            payload = generatePayload(args.repo, commitsha)
                            notifyDeployment(payload, webhook, args.dlbearer)
                        }
                        else {
                            println ("No valid commitsha. Not executing notification script.")
                        }
                    }
                    else {
                        println("Team ${team} has no corresponding webhook in DevLake")
                    }
                }
            }

        }
        else {
            println "main function returned return code: ${getRC}"
            println get
        }
    }
    catch (Exception e) {
        println "Error: ${e}"
    }
}
/**
* Obtain the github commit sha corresponding to the release tag
*/
def commitSha(repo, version, ghbearer) {
    try {
        def get = new URL('https://api.github.com/repos/plugsurfing/' + repo + '/git/ref/tags/' + version).openConnection()
        get.requestMethod = 'GET'
        get.setRequestProperty('Content-Type', 'application/json')
        get.setRequestProperty('Authorization', 'Bearer ' + ghbearer)
        get.setRequestProperty('X-GitHub-Api-Version', '2022-11-28')
        getRC = get.getResponseCode()

        if (debug) {
        println("- DEBUG: Requesting sha for repo: ${repo}, version: ${version}")
        }

        if (getRC == (200)) {
            response = get.inputStream.getText()
            shaJson = new JsonSlurperClassic().parseText(response)
            sha = shaJson.object.sha
            return sha
        }
        else {
            println "no valid commit sha. Returned response code: ${getRC}"
        }
    }
    catch (Exception e) {
        println "Error: ${e}"
    }
}

/**
* Obtain the correct webhook based on the team name in DevLake
*/
def getWebhook(teamName, dlbearer) {
    webhook = teamName + '-webhook'

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
                return match.postPipelineDeployTaskEndpoint
            }
            else {
                return null
            }
        }
        else {
            println "getWebhook function returned response code: ${getRC}"
        }
    }
    catch (Exception e) {
        println "Error: ${e}"
    }
}

/**
* Call the DevLake deployment webhook
*/
def notifyDeployment(payload, webhook, dlbearer) {
    def devlakePublish = """
            curl https://devlake-configui.central.plugsurfing-infra.com/api${webhook} -X 'POST' -H 'Authorization: Bearer
            ${dlbearer}' -d '${payload}'
        """

    println("Notifying DevLake. Curl command: ${devlakePublish}")
}


def generatePayload(repo, commitsha) {

    // Process deployment data
    def buildEndTime = currentBuild.startTimeInMillis + currentBuild.duration
    def buildEndTimeClean = new Date(buildEndTime).format('yyyy-MM-dd HH:mm:ss')
    def buildStartTimeClean = new Date(currentBuild.startTimeInMillis).format('yyyy-MM-dd HH:mm:ss')

    jsonPayload = JsonOutput.toJson([deploymentCommits: [
        [
            startedDate: "${buildStartTimeClean}",
            finishedDate:"${buildEndTimeClean}",
            commitSha: "${commitsha}",
            repoUrl: "https://github.com/plugsurfing/${repo}"
        ]
    ],
    id: "${commitsha}",
    startedDate: "${buildStartTimeClean}",
    finishedDate:"${buildEndTimeClean}"
    ])

    jsonPretty = JsonOutput.prettyPrint(jsonPayload)
    return jsonPretty
}
