folder("release") {
    description("This folder contains all jobs used by developers for upstream release and all relevant stuff")
    displayName("Release")
}

pipelineJob('release/release-debezium-upstream') {
    displayName('Debezium Charts Release')
    description('Packages helm charts push into Quay.io')

    properties {
        githubProjectUrl('https://github.com/debezium/debezium')
    }

    logRotator {
        numToKeep(5)
    }

    parameters {
        stringParam('MAIL_TO', 'mvitale@redhat.com')
        stringParam('RELEASE_VERSION', 'x.y.z.Final', 'Version of Debezium to be released - e.g. 0.5.2.Final')
    }

    definition {
        cps {
            script(readFileFromWorkspace('jenkins-jobs/pipelines/release/release-charts-pipeline.groovy'))
        }
    }
}
