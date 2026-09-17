plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    // Applied (not `apply false`) — the scanner runs from the root project and
    // walks the subprojects itself.
    alias(libs.plugins.sonarqube)
}

// Static-analysis config for the local SonarQube instance. The auth token is
// deliberately NOT here — pass it as `-Dsonar.token=...` or, better, put
// `systemProp.sonar.token=...` in the *user* gradle.properties (~/.gradle/),
// which is outside the repo.
sonar {
    properties {
        property("sonar.projectKey", "ankiimageandroid")
        property("sonar.projectName", "ankiimageandroid")
        property("sonar.host.url", "http://localhost:9000")
        // The scanner forks its own JVM and defaults max heap to 1/4 of
        // physical RAM (~4g on this 16g box). Stacked on the Gradle daemon,
        // the Kotlin daemon and the local SonarQube server, that exhausts the
        // Windows commit charge and the fork dies with
        // "The paging file is too small for this operation to complete".
        // 1g is ample for a codebase this size.
        property("sonar.scanner.javaOpts", "-Xmx1g")
    }
}
