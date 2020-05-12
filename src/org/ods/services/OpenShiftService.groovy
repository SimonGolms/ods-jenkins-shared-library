package org.ods.services

import groovy.json.JsonSlurperClassic

import org.ods.util.ILogger
import org.ods.util.IPipelineSteps

@SuppressWarnings('MethodCount')
class OpenShiftService {

    private final IPipelineSteps steps
    private final ILogger logger
    private final String project
    private String appDomain

    OpenShiftService(IPipelineSteps steps, ILogger logger, String project) {
        this.steps = steps
        this.logger = logger
        this.project = project
    }

    static void createProject(IPipelineSteps steps, String name) {
        steps.sh(
            script: "oc new-project ${name}",
            label: "Create new OpenShift project ${name}"
        )
    }

    static void loginToExternalCluster(IPipelineSteps steps, String apiUrl, String apiToken) {
        steps.sh(
            script: """oc login ${apiUrl} --token=${apiToken} >& /dev/null""",
            label: "login to external cluster (${apiUrl})"
        )
    }

    static String getApiUrl(IPipelineSteps steps) {
        steps.sh(
            script: 'oc whoami --show-server',
            label: 'Get OpenShift API server URL',
            returnStdout: true
        ).trim()
    }

    static boolean tooManyEnvironments(IPipelineSteps steps, String projectId, Integer limit) {
        steps.sh(
            returnStdout: true,
            script: "oc projects | grep '^\\s*${projectId}-' | wc -l",
            label: "check ocp environment maximum for '${projectId}-*'"
        ).trim().toInteger() >= limit
    }

    static boolean envExists(IPipelineSteps steps, String project) {
        def exists = steps.sh(
            script: "oc project ${project} &> /dev/null",
            label: "Check if OpenShift project '${project}' exists",
            returnStatus: true
        ) == 0
        steps.echo("OpenShift project '${project}' ${exists ? 'exists' : 'does not exist'}")
        exists
    }

    boolean envExists() {
        envExists(steps, project)
    }

    def createVersionedDevelopmentEnvironment(String projectKey, String sourceEnvName) {
        def limit = 3
        if (tooManyEnvironments(steps, "${projectKey}-dev-", limit)) {
            throw new RuntimeException(
                "Error: only ${limit} versioned ${projectKey}-dev-* environments are allowed. " +
                'Please clean up and run the pipeline again.'
            )
        }
        def tmpDir = "tmp-${project}"
        steps.sh(
            script: "mkdir -p ${tmpDir}",
            label: "Ensure ${tmpDir} exists"
        )
        steps.dir(tmpDir) {
            createProject(steps, project)
            doTailorExport("${projectKey}-${sourceEnvName}", 'serviceaccount,rolebinding', [:], 'template.yml')
            doTailorApply('serviceaccount,rolebinding --upsert-only')
            steps.deleteDir()
        }
    }

    String getApiUrl() {
        getApiUrl(steps)
    }

    @SuppressWarnings(['LineLength', 'ParameterCount'])
    void tailorApply(Map<String, String> target, String paramFile, List<String> params, List<String> preserve, String tailorPrivateKeyFile, boolean verify) {
        def verifyFlag = verify ? '--verify' : ''
        def tailorPrivateKeyFlag = tailorPrivateKeyFile ? "--private-key ${tailorPrivateKeyFile}" : ''
        def selectorFlag = target.selector ? "--selector ${target.selector}" : ''
        def excludeFlag = target.exclude ? "--exclude ${target.exclude}" : ''
        def includeArg = target.include ?: ''
        def paramFileFlag = paramFile ? "--param-file ${paramFile}" : ''
        params << "ODS_OPENSHIFT_APP_DOMAIN=${getApplicationDomain()}"
        def paramFlags = params.collect { "--param ${it}" }.join(' ')
        def preserveFlags = preserve.collect { "--preserve ${it}" }.join(' ')
        doTailorApply("${selectorFlag} ${excludeFlag} ${paramFlags} ${preserveFlags} ${paramFileFlag} ${tailorPrivateKeyFlag} ${verifyFlag} --ignore-unknown-parameters ${includeArg}")
    }

    void tailorExport(String selector, Map<String, String> envParams, String targetFile) {
        doTailorExport(project, "-l ${selector}", envParams, targetFile)
    }

    void startRollout(String name) {
        steps.sh(
            script: "oc -n ${project} rollout latest dc/${name}",
            label: "Rollout latest version of dc/${name}"
        )
    }

    void watchRollout(String name) {
        steps.sh(
            script: "oc -n ${project} rollout status dc/${name} --watch=true",
            label: "Watch rollout of latest version of dc/${name}"
        )
    }

    int getLatestVersion(String name) {
        def versionNumber = steps.sh(
            script: "oc -n ${project} get dc/${name} -o jsonpath='{.status.latestVersion}'",
            label: "Get latest version of dc/${name}",
            returnStdout: true
        ).trim()
        if (!versionNumber.isInteger()) {
            throw new RuntimeException(
                "ERROR: Latest version of DeploymentConfig '${name}' is not a number: '${versionNumber}"
            )
        }
        versionNumber as int
    }

    String getRolloutStatus(String name) {
        def jsonPath = '{.metadata.annotations.openshift\\.io/deployment\\.phase}'
        steps.sh(
            script: "oc -n ${project} get rc/${name} -o jsonpath='${jsonPath}'",
            label: "Get status of ReplicationController ${name}",
            returnStdout: true
        ).trim().toLowerCase()
    }

    void setImageTag(String name, String sourceTag, String destinationTag) {
        steps.sh(
            script: "oc -n ${project} tag ${name}:${sourceTag} ${name}:${destinationTag}",
            label: "Set tag ${destinationTag} on is/${name}"
        )
    }

    boolean resourceExists(String kind, String name) {
        steps.sh(
            script: "oc -n ${project} get ${kind}/${name} &> /dev/null",
            returnStatus: true,
            label: "Check existance of ${kind} ${name}"
        ) == 0
    }

    String startAndFollowBuild(String name, String dir) {
        steps.sh(
            script: "oc -n ${project} start-build ${name} --from-dir ${dir} --follow",
            label: "Start and follow OpenShift build ${name}",
            returnStdout: true
        ).trim()
    }

    int getLastBuildVersion(String name) {
        def versionNumber = steps.sh(
            returnStdout: true,
            script: "oc -n ${project} get bc/${name} -o jsonpath='{.status.lastVersion}'",
            label: "Get lastVersion of BuildConfig ${name}"
        ).trim()
        if (!versionNumber.isInteger()) {
            throw new RuntimeException(
                "ERROR: Last version of BuildConfig '${name}' is not a number: '${versionNumber}"
            )
        }
        versionNumber as int
    }

    String getBuildStatus(String buildId) {
        def buildStatus = 'unknown'
        def retries = 3
        for (def i = 0; i < retries; i++) {
            buildStatus = checkForBuildStatus(buildId)
            if (buildStatus == 'complete') {
                return buildStatus
            }
            // Wait 5 seconds before asking again. Sometimes the build finishes but the
            // status is not set to "complete" immediately ...
            steps.sleep(5)
        }
        return buildStatus
    }

    @SuppressWarnings('LineLength')
    void patchBuildConfig(String name, String tag, Map buildArgs, Map imageLabels) {
        def odsImageLabels = []
        for (def key : imageLabels.keySet()) {
            odsImageLabels << """{"name": "${key}", "value": "${imageLabels[key]}"}"""
        }

        // Normally we want to replace the imageLabels, but in case they are not
        // present yet, we need to add them this time.
        def imageLabelsOp = 'replace'
        def imageLabelsValue = steps.sh(
            script: "oc -n ${project} get bc/${name} -o jsonpath='{.spec.output.imageLabels}'",
            returnStdout: true,
            label: 'Test existance of path .spec.output.imageLabels'
        ).trim()
        if (imageLabelsValue.length() == 0) {
            imageLabelsOp = 'add'
        }

        def patches = [
            '{"op": "replace", "path": "/spec/source", "value": {"type":"Binary"}}',
            """{"op": "replace", "path": "/spec/output/to/name", "value": "${name}:${tag}"}""",
            """{"op": "${imageLabelsOp}", "path": "/spec/output/imageLabels", "value": [
              ${odsImageLabels.join(',')}
            ]}""",
        ]

        // Add build args
        def buildArgsItems = []
        for (def key : buildArgs.keySet()) {
            def val = buildArgs[key]
            buildArgsItems << """{"name": "${key}", "value": "${val}"}"""
        }
        if (buildArgsItems.size() > 0) {
            def buildArgsPatch = """{"op": "replace", "path": "/spec/strategy/dockerStrategy/buildArgs", "value": [${buildArgsItems.join(',')}]}"""
            patches << buildArgsPatch
        }

        steps.sh(
            script: """oc -n ${project} patch bc ${name} --type=json --patch '[${patches.join(',')}]'""",
            label: "Patch BuildConfig ${name}"
        )
    }

    String getImageReference(String name, String tag) {
        steps.sh(
            returnStdout: true,
            script: "oc -n ${project} get istag ${name}:${tag} -o jsonpath='{.image.dockerImageReference}'",
            label: "Get image reference of ${name}:${tag}"
        ).trim()
    }

    String getContainerForImage(String projectId, String rc, String image) {
        def jsonPath = """{.spec.template.spec.containers[?(contains .image "${image}")].name}"""
        steps.sh(
            script: "oc -n ${projectId} get rc ${rc} -o jsonpath='${jsonPath}'",
            returnStdout: true,
            label: "Getting containers for ${rc} and image ${image}"
        )
    }

    // getRunningImageSha returns the sha with "sha256:" prefix, e.g.
    // sha256:eec4a4451a307bd1fa44bde6642077a3c2a722e0ad370c1c22fcebcd8d4efd33
    String getRunningImageSha(String component, String version, index = 0) {
        def runningImageUrl = steps.sh(
            script: """oc -n ${project} get rc/${component}-${version} \
            -o jsonpath='{.spec.template.spec.containers[${index}].image}'
            """,
            label: "Get running image for rc/${component}-${version} containerIndex: ${index}",
            returnStdout: true
        ).trim()
        getImageInformationFromImageUrl(runningImageUrl).sha
    }

    void importImageFromProject(String name, String sourceProject, String imageSha, String imageTag) {
        steps.sh(
            script: """oc -n ${project} tag ${sourceProject}/${name}@${imageSha} ${name}:${imageTag}""",
            label: "Tag image ${name} into ${project}"
        )
    }

    void importImageFromSourceRegistry(String name, String sourceProject, String imageSha, String imageTag) {
        def sourceClusterRegistryHost = getSourceClusterRegistryHost()
        def sourceImage = "${sourceClusterRegistryHost}/${sourceProject}/${name}@${imageSha}"
        steps.sh(
            script: """
              oc -n ${project} import-image ${name}:${imageTag} \
                --from=${sourceImage} \
                --confirm
            """,
            label: "Import image ${sourceImage} into ${project}/${name}:${imageTag}"
        )
    }

    // Gets pod of deployment
    Map getPodDataForDeployment(String rc) {
        def index = 5
        while (index > 0) {
            def podStatus = steps.sh(
                script: "oc -n ${project} get pod -l deployment=${rc} -o jsonpath='{.items[*].status.phase}'",
                returnStdout: true,
                label: "Getting OpenShift pod data for deployment ${rc}"
            )
            if (podStatus && podStatus == 'Running') {
                break
            } else {
                steps.sleep(60)
                index--
            }
        }

        def stdout = steps.sh(
            script: "oc -n ${project} get pod -l deployment=${rc} -o json",
            returnStdout: true,
            label: "Getting OpenShift pod data for deployment ${rc}"
        ).trim()

        extractPodData(stdout, "deployment '${rc}'")
    }

    List getDeploymentConfigsForComponent(String componentSelector) {
        steps.sh(
            script: "oc -n ${project} get dc -l ${componentSelector} -o jsonpath='{.items[*].metadata.name}'",
            returnStdout: true,
            label: "Getting all DeploymentConfig names for selector '${componentSelector}'"
        ).trim().tokenize(' ')
    }

    String getApplicationDomain() {
        if (!this.appDomain) {
            this.appDomain = getApplicationDomainOfProject(project)
        }
        this.appDomain
    }

    // getImageInformationFromImageUrl expects an image URL like one of the following:
    // 172.30.21.196:5000/foo/bar:2-3ec425bc
    // 172.30.21.196:5000/foo/bar@sha256:eec4a4451a307bd1fa44bde6642077a3c2a722e0ad370c1c22fcebcd8d4efd33
    //
    // It returns a map with image parts:
    // - registry
    // - repository (= OpenShift project in case of image from ImageStream)
    // - name (= ImageStream name in case of image from ImageStream)
    // - reference (= <name>@sha256:<sha-identifier>)
    // - sha (= sha256:<sha-identifier>)
    // - shaStripped (= <sha-identifier>)
    Map<String, String> getImageInformationFromImageUrl(String url) {
        steps.echo("Deciphering image URL ${url} into pieces")
        def imageInfo = [:]

        def urlParts = url.split('/')
        if (urlParts.size() != 3) {
            throw new RuntimeException(
                "ERROR: Image URL ${url} does not consist of three parts (registry/repository/reference)"
            )
        }
        imageInfo['registry'] = urlParts[0]
        imageInfo['repository'] = urlParts[1]

        // If last part of URL contains a tag, resolve to SHA
        if (urlParts[2].contains('@')) {
            imageInfo['reference'] = urlParts[2]
        } else {
            def tagParts = urlParts[2].split(':')
            def shaUrl = getImageReference(tagParts[0], tagParts[1])
            def shaUrlParts = shaUrl.split('/')
            if (shaUrlParts.size() != 3) {
                throw new RuntimeException(
                    "ERROR: Image URL ${shaUrl} does not consist of three parts (registry/repository/reference)"
                )
            }
            imageInfo['reference'] = shaUrlParts[2]
        }

        if (!imageInfo['reference'].contains('@')) {
            throw new RuntimeException(
                "ERROR: Image reference ${imageInfo['reference']} does not contain a sha256 identifier"
            )
        }

        def referenceParts = imageInfo['reference'].split('@')
        if (referenceParts.size() != 2) {
            throw new RuntimeException(
                "ERROR: Image reference ${imageInfo['reference']} does not consist of two parts (name@sha)"
            )
        }
        imageInfo['name'] = referenceParts[0]
        imageInfo['sha'] = referenceParts[1]
        imageInfo['shaStripped'] = referenceParts[1].replace('sha256:', '')

        imageInfo
    }

    List<Map<String, String>> getImageStreamsForDeploymentConfig(String dc) {
        def imageString = steps.sh (
            script: "oc -n ${project} get dc ${dc} -o jsonpath='{.spec.template.spec.containers[*].image}'",
            label: "Get container images for deploymentconfigs (${dc})",
            returnStdout: true
        )
        imageString.tokenize(' ').collect { getImageInformationFromImageUrl(it) }
    }

    String getOriginUrlFromBuildConfig(String bcName) {
        return steps.sh(
            script: "oc -n ${project} get bc/${bcName} -o jsonpath='{.spec.source.git.uri}'",
            returnStdout: true,
            label: "get origin from openshift bc ${bcName}"
        ).trim()
    }

    @SuppressWarnings(['CyclomaticComplexity', 'AbcMetric'])
    private Map extractPodData(String ocOutput, String description) {
        def j = new JsonSlurperClassic().parseText(ocOutput)
        if (j?.items[0]?.status?.phase?.toLowerCase() != 'running') {
            throw new RuntimeException("Error: no pod for ${description} running / found.")
        }

        def podOCData = j.items[0] ?: [:]

        def pod = [:]
        // Only set needed data on "pod"
        pod.podName = podOCData.metadata?.name ?: 'N/A'
        pod.podNamespace = podOCData.metadata?.namespace ?: 'N/A'
        pod.podMetaDataCreationTimestamp = podOCData.metadata?.creationTimestamp ?: 'N/A'
        pod.deploymentId = 'N/A'
        if (podOCData.metadata?.annotations && podOCData.metadata.annotations['openshift.io/deployment.name']) {
            pod.deploymentId = podOCData.metadata.annotations['openshift.io/deployment.name']
        }
        pod.podNode = podOCData.spec?.nodeName ?: 'N/A'
        pod.podIp = podOCData.status?.podIP ?: 'N/A'
        pod.podStatus = podOCData.status?.phase ?: 'N/A'
        pod.podStartupTimeStamp = podOCData.status?.startTime ?: 'N/A'
        pod.containers = [:]
        podOCData.spec?.containers?.each { container ->
            pod.containers[container.name] = container.image
        }
        pod
    }

    private String tailorVerboseFlag() {
        steps.env.DEBUG ? '--verbose' : ''
    }

    private void doTailorApply(String tailorParams) {
        steps.sh(
            script: """tailor \
              ${tailorVerboseFlag()} \
              --non-interactive \
              -n ${project} \
              apply ${tailorParams}""",
            label: "tailor apply for ${project} (${tailorParams})"
        )
    }

    private void doTailorExport(
        String exportProject,
        String tailorParams,
        Map<String,
        String> envParams,
        String targetFile) {
        envParams['TAILOR_NAMESPACE'] = exportProject
        envParams['ODS_OPENSHIFT_APP_DOMAIN'] = getApplicationDomainOfProject(exportProject)
        def templateParams = ''
        def sedReplacements = ''
        envParams.each { key, val ->
            sedReplacements += "s|${val}|\\\${${key}}|g;"
            templateParams += "- name: ${key}\n  required: true\n"
        }
        steps.sh(
            script: """
                tailor \
                    ${tailorVerboseFlag()} \
                    -n ${exportProject} \
                    export ${tailorParams} > ${targetFile}
                    sed -i -e "${sedReplacements}" ${targetFile}
                    echo "parameters:" >> ${targetFile}
                    echo "${templateParams}" >> ${targetFile}
            """,
            label: "tailor export of ${exportProject} (${tailorParams}) into ${targetFile}"
        )
    }

    private String getApplicationDomainOfProject(String appProject) {
        def routeName = 'test-route-' + System.currentTimeMillis()
        steps.sh (
            script: "oc -n ${appProject} create route edge ${routeName} --service=dummy --port=80 | true",
            label: "create dummy route for extraction (${routeName})"
        )
        def routeUrl = steps.sh (
            script: "oc -n ${appProject} get route ${routeName} -o jsonpath='{.spec.host}'",
            returnStdout: true,
            label: 'get cluster route domain'
        )
        def routePrefixLength = "${routeName}-${appProject}".length() + 1
        def openShiftPublicHost = routeUrl[routePrefixLength..-1]
        steps.sh (
            script: "oc -n ${appProject} delete route ${routeName} | true",
            label: "delete dummy route for extraction (${routeName})"
        )
        return openShiftPublicHost
    }

    private String getSourceClusterRegistryHost() {
        def secretName = 'mro-image-pull'
        def dockerConfig = steps.sh(
            script: """
            oc -n ${project} get secret ${secretName} --output="jsonpath={.data.\\.dockerconfigjson}" | base64 --decode
            """,
            returnStdout: true,
            label: "read source cluster registry host from ${secretName}"
        )
        def dockerConfigJson = steps.readJSON(text: dockerConfig)
        def auths = dockerConfigJson.auths
        def authKeys = auths.keySet()
        if (authKeys.size() > 1) {
            throw new RuntimeException(
                "Error: 'dockerconfigjson' of secret '${secretName}' has more than one registry host entry."
            )
        }
        authKeys.first()
    }

    private String checkForBuildStatus(String buildId) {
        steps.sh(
            returnStdout: true,
            script: "oc -n ${project} get build ${buildId} -o jsonpath='{.status.phase}'",
            label: "Get phase of build ${buildId}"
        ).trim().toLowerCase()
    }

}
