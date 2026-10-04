plugins {
    id("nordtal.jvm-app")
    // FakeDaemon and the stand-in agent, which steward's tests talk to as well.
    `java-test-fixtures`
    id("nordtal.plugin-descriptor")
}

// How Steward shows this module's settings; steward-agent reads it out of the jar.
pluginDescriptor {
    displayName.set("Steward agent")
}

repositories {
    maven("https://jitpack.io")
}

application.mainClass.set("eu.nordtal.s2.stewardagent.StewardAgent")

// ComposeRefusesItselfTest reads the real compose file, so it has to be a declared input.
// Files outside this module that the topology tests read as text, so an edit to one reruns :steward-agent:test.
repositoryRootTestInputs {
    reads("compose.yml")
    reads("deploy/dev.env.example")
    reads("deploy/jvm/Dockerfile")
    reads("deploy/minecraft/entrypoint.sh")
    reads(".dockerignore")
    reads(".github/workflows/release.yml")
    reads("smp/src/main/resources/paper-plugin.yml")
}

dependencies {
    // Only this service creates containers; steward asks it over the guarded internal API.
    implementation(project(":internal-api"))
    implementation(libs.gson)

    // The kernel, for the process clock; it depends on the JDK alone.
    implementation(project(":common"))

    // The runs: the inbox, the settings and the schema. jcore carries the config specs and Flyway at runtime.
    implementation(libs.jcore)
    // :database takes the driver, JDBI, HikariCP and slf4j compileOnly; the bundle puts them on the runtime path.
    implementation(project(":database"))
    implementation(libs.bundles.access.persistence)
    implementation(libs.postgresql.driver)
    // JdbiPluginDirectory installs JDBI's PostgresPlugin, which jcore declares at runtime scope only.
    implementation(libs.jdbi.postgres)
    // Compile-only for Schema, which passes the role placeholders: jcore ships Flyway at runtime.
    compileOnly(libs.flyway.core)
    implementation(project(":settings"))

    runtimeOnly(libs.logback.classic)

    // The stand-in agent: the real routes over FakeDaemon, behind the real guard.
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
