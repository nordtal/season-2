plugins {
    id("nordtal.jvm-app")
}

application.mainClass.set("eu.nordtal.s2.steward.deployer.StewardDeployer")

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
