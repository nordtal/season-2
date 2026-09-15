plugins {
    id("nordtal.jvm-app")
}

application.mainClass.set("eu.nordtal.s2.steward.deployer.StewardDeployer")

// ComposeRefusesItselfTest reads the real compose file to check that the service this process
// refuses to recreate is a service that exists. Without the declaration Gradle cannot see the file,
// an edit to it does not re-run the test, and the check silently stops running.
repositoryRootTestInputs {
    reads("compose.yml")
}

dependencies {
    // A small internal API on the steward network: steward-ui owns no docker socket and no compose
    // binary, so "recreate this service now" has to be a request to the one service that does.
    implementation(libs.javalin)
    implementation(libs.gson)

    runtimeOnly(libs.logback.classic)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

// compose.yml is baked into the image, so it has to be in the build context first. Gradle copies
// it rather than the Dockerfile reaching up out of its context - a context that is the repository
// root would ship the whole tree to the daemon on every build.
val stageComposeFile by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.file("compose.yml"))
    into(layout.buildDirectory.dir("compose"))
}

tasks.named("build") {
    dependsOn(stageComposeFile)
}
