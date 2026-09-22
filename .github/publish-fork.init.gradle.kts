// Fork-only: lets `publish` run against GitHub Packages without Sonatype credentials.
//
// Every mediamp module calls vanniktech's publishToMavenCentral(), which wires a
// `prepareMavenCentralPublishing` task into the `publish` lifecycle. That task requires
// ORG_GRADLE_PROJECT_mavenCentralUsername/Password and fails the build when they are absent,
// which they always are on this fork.
//
// MavenPublication repositories are untouched: this only removes the Maven Central jobs from
// the `publish` aggregate. Use the `publishToMavenCentral` aggregate instead if you ever do
// publish to Sonatype.
//
// Usage: --init-script .github/publish-fork.init.gradle.kts

gradle.projectsEvaluated {
    allprojects {
        tasks.matching { it.name.contains("MavenCentral") }.configureEach {
            enabled = false
        }
        tasks.matching { it.name == "publish" }.configureEach {
            setDependsOn(dependsOn.filterNot { dep ->
                dep.toString().contains("MavenCentral", ignoreCase = true)
            })
        }
    }
}
