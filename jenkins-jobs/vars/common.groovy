def validateVersionFormat(version) {
    if (!(version ==~ /\d+\.\d+.\d+\.(Final|(Alpha|Beta|CR)\d+)/)) {
        error "Release version '$version' is not of the required format x.y.z.suffix"
    }
}

def getDryRun() {

    if (DRY_RUN == null) {
        DRY_RUN = false
    }
    else if (DRY_RUN instanceof String) {
        DRY_RUN = Boolean.valueOf(DRY_RUN)
    }
    echo "Dry run: ${DRY_RUN}"

    return DRY_RUN
}

static String convertToSemver(String releaseTag) {

    def baseVersionMatcher = releaseTag =~ /^(\d+\.\d+\.\d+)\..*$/
    def baseVersion = baseVersionMatcher ? baseVersionMatcher[0][1] : releaseTag

    if (releaseTag.endsWith(".Final")) {
        return baseVersion
    } else {

        def prereleasePartMatcher = releaseTag =~ /^${baseVersion}\.(.*)$/
        def prereleasePart = prereleasePartMatcher ? prereleasePartMatcher[0][1] : ""

        def semverPrerelease = prereleasePart.toLowerCase().replace('.', '-')

        return "${baseVersion}-${semverPrerelease}"
    }
}
