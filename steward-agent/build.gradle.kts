plugins {
    id("nordtal.jvm-app")
    // FakeDaemon and the stand-in agent, which steward's tests talk to as well.
    `java-test-fixtures`
    id("nordtal.plugin-descriptor")
}

application.mainClass.set("eu.nordtal.season.stewardagent.StewardAgent")

// The topology tests read these files outside the module as text, so an edit reruns :steward-agent:test.
repositoryRootTestInputs {
    reads("compose.yml")
    reads("deploy/dev.env.example")
    readsTree("deploy/caddy")
    reads("deploy/jvm/Dockerfile")
    reads("deploy/jvm/entrypoint.sh")
    reads("deploy/minecraft/Dockerfile")
    reads("deploy/minecraft/entrypoint.sh")
    reads(".dockerignore")
    reads(".github/workflows/release.yml")
    reads("smp/src/main/resources/paper-plugin.yml")
}

dependencies {
    implementation(project(":internal-api"))
    implementation(libs.gson)

    // The process clock.
    implementation(project(":common"))

    // :database takes the driver, JDBI, HikariCP and slf4j as compileOnly, so the bundle adds them.
    implementation(project(":database"))
    implementation(libs.bundles.access.persistence)
    implementation(libs.postgresql.driver)
    implementation(libs.jdbi.postgres)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    implementation(project(":settings"))

    runtimeOnly(libs.logback.classic)

    testImplementation(testFixtures(project(":database")))
    testImplementation(testFixtures(project(":settings")))
    testImplementation(testFixtures(project(":common")))
    testImplementation(libs.logback.classic)

    testFixturesImplementation(project(":internal-api"))
    testFixturesImplementation(project(":common"))
    testFixturesImplementation(testFixtures(project(":common")))
    testFixturesImplementation(libs.gson)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

// compose.yml is baked into the image beside the jar.
tasks.named<Sync>("imageContext") {
    from(rootProject.layout.projectDirectory.file("compose.yml"))
}
