// import groovy.json.JsonSlurper
import groovy.transform.Field

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
* Obtain the correct webhook based on the team name in DevLake
*/
def call(repo, bearer) {

    try {
        def get = new URL('https://api.github.com/repos/plugsurfing/' + repo + '/teams').openConnection()
        get.requestMethod = 'GET'
        get.setRequestProperty('Content-Type', 'application/json')
        get.setRequestProperty('Authorization', 'Bearer ' + bearer)
        get.setRequestProperty('X-GitHub-Api-Version', '2022-11-28') 
        getRC = get.getResponseCode()
        println "Echoing something"

        if (getRC == (200)) {
            // def response = get.getInputStream().getText()
            response = get.inputStream.getText()
            // json = new JsonSlurper().parseText(response)
            json = sh(script:'jq . < ${response}', returnStdout:true).trim()
            println "lol"
            println json
            


            for (team in json) {
                println "Team name:"
                println team.name
            }
            // return match.postPipelineDeployTaskEndpoint
        }
    }
    catch (Exception e) {
        println "Error: ${e}"
    }
}
