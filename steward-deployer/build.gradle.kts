plugins {
    id("nordtal.jvm-app")
}

application.mainClass.set("eu.nordtal.s2.steward.deployer.StewardDeployer")

// ComposeRefusesItselfTest reads the real compose file, so it has to be a declared input.
repositoryRootTestInputs {
    reads("compose.yml")
}

dependencies {
    // Only this service creates containers; steward asks it to recreate one over HTTP.
    implementation(libs.javalin)
    implementation(libs.gson)

    // The kernel, for the process clock; it depends on the JDK alone.
    implementation(project(":common"))

    runtimeOnly(libs.logback.classic)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

// compose.yml is baked into the image, so Gradle copies it into the build context.
val stageComposeFile by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.file("compose.yml"))
    into(layout.buildDirectory.dir("compose"))
}

tasks.named("build") {
    dependsOn(stageComposeFile)
}
