plugins {
    id("nordtal.jvm-app")
}

application.mainClass.set("eu.nordtal.s2.steward.worker.StewardWorker")

// Handover compares this version with the one a run was handed to.
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    manifest {
        attributes["Implementation-Version"] = project.version.toString()
    }
}

// Tests that read files as text declare them here, or editing one alone leaves the tests UP-TO-DATE.
repositoryRootTestInputs {
    reads("compose.yml")

    // DocumentedCommandsTest reads every document that shows a `steward-worker` command.
    reads(".env.example")
    reads("steward-worker/Dockerfile")
    reads("steward-worker/README.md")
    reads("deploy/README.md")

    // Read as text by the tests that assert statement order inside these sequences.
    reads("steward-worker/src/main/java/eu/nordtal/s2/steward/worker/serve/Runner.java")
    reads("steward-worker/src/main/java/eu/nordtal/s2/steward/worker/serve/BackupSequence.java")
    reads("steward-worker/src/main/java/eu/nordtal/s2/steward/worker/serve/RestartSequence.java")
    reads("steward-worker/src/main/java/eu/nordtal/s2/steward/worker/serve/UpdateSequence.java")

    // HeartbeatLeavesTheTimerTest asserts which executor writes the heartbeat.
    reads("steward-worker/src/main/java/eu/nordtal/s2/steward/worker/api/WorkerApi.java")

    // TopologyTest checks that the release workflow pushes every image compose.yml names.
    reads(".github/workflows/release.yml")

    // TopologyDeploymentTest holds dev.env.example against every required variable in compose.yml.
    reads("deploy/dev.env.example")
}

repositories {
    maven("https://jitpack.io")
}

dependencies {
    // NullAway's annotations name checker-qual's TypeUseLocation; without it javac warns and -Werror fails.
    compileOnly("org.checkerframework:checker-qual:4.2.3")
    testCompileOnly("org.checkerframework:checker-qual:4.2.3")

    // The internal API steward-ui calls, kept out of steward-ui so the web layer holds no docker socket.
    implementation(libs.javalin)

    // jcore exports gson and snakeyaml, so nothing here declares a parser of its own.
    implementation(libs.jcore)

    // jcore exports no logging backend, and without one every log line disappears.
    runtimeOnly(libs.logback.classic)

    // WorkerShutdownTest counts logged warnings.
    testImplementation(libs.logback.classic)

    // BunqRequestBuilder patches the SDK's own class; read it before touching this or the OkHttp version.
    implementation(libs.bunq.sdk)

    // The patched BunqRequestBuilder extends okhttp3.Request.Builder, which the SDK ships at runtime scope only.
    implementation(libs.okhttp)

    // :database takes the driver compileOnly; the pool and the signal hub need it at runtime.
    implementation(libs.postgresql.driver)

    // Shaded in, and with it the migration SQL.
    implementation(project(":database"))
    // The one loader, database.yml and checks every process shares.
    implementation(project(":settings"))
    // Compile-only for Schema, which passes the role placeholders: jcore ships Flyway at runtime.
    compileOnly(libs.flyway.core)
    // JdbiPluginDirectory installs JDBI's PostgresPlugin, which jcore declares at runtime scope only.
    implementation(libs.jdbi.postgres)
    testImplementation(testFixtures(project(":database")))

    // ConfigFilesOwnershipTest needs a file owned by another user, which only an in-memory filesystem gives.
    testImplementation(libs.jimfs)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}
