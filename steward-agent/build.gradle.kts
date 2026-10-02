plugins {
    id("nordtal.jvm-app")
    // FakeDaemon and the stand-in agent, which steward's tests talk to as well.
    `java-test-fixtures`
}

application.mainClass.set("eu.nordtal.s2.stewardagent.StewardAgent")

// ComposeRefusesItselfTest reads the real compose file, so it has to be a declared input.
repositoryRootTestInputs {
    reads("compose.yml")
}

dependencies {
    // Only this service creates containers; steward asks it over the guarded internal API.
    implementation(project(":internal-api"))
    implementation(libs.gson)

    // The kernel, for the process clock; it depends on the JDK alone.
    implementation(project(":common"))

    runtimeOnly(libs.logback.classic)

    // The stand-in agent: the real routes over FakeDaemon, behind the real guard.
    testFixturesImplementation(project(":internal-api"))
    testFixturesImplementation(libs.gson)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

// Beside build/libs, whose one jar is what the Dockerfile's glob copies into the image.
tasks.named<Jar>("testFixturesJar") {
    destinationDirectory.set(layout.buildDirectory.dir("test-fixtures"))
}

// compose.yml is baked into the image, so Gradle copies it into the build context.
val stageComposeFile by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.file("compose.yml"))
    into(layout.buildDirectory.dir("compose"))
}

tasks.named("build") {
    dependsOn(stageComposeFile)
}
