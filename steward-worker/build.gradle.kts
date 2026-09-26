plugins {
    id("nordtal.jvm-app")
}

application.mainClass.set("eu.nordtal.s2.steward.worker.StewardWorker")

// TopologyTest reads the real compose.yml; without declaring it, editing that file alone would
// leave :steward-worker:test UP-TO-DATE.
repositoryRootTestInputs {
    reads("compose.yml")

    // DocumentedCommandsTest reads every file that writes `docker compose run --rm steward-worker` down for
    // a person to copy, so those documents have to be inputs too.
    reads(".env.example")
    reads("steward-worker/Dockerfile")
    reads("steward-worker/README.md")
    reads("deploy/README.md")

    // Runner as text, for the two tests that read it rather than call it.
    // CountdownComesAfterResolvingTest asserts the order of two statements inside one method, and
    // BackupSavesBeforeItSettlesTest asserts that the backup is written before the run decides it
    // is a failure. Gradle's own input is the compiled class, which says nothing about either. If
    // one of those tests is ever deleted, leave this line: the other one still needs it, and a
    // declaration that quietly goes with the wrong test is exactly the failure they guard against.
    reads("steward-worker/src/main/java/eu/nordtal/s2/steward/worker/serve/Runner.java")

    // And WorkerApi as text, for the same reason: HeartbeatLeavesTheTimerTest asserts which
    // executor the heartbeat comment is written on, and folding that back into the timer's own
    // lambda is a change Gradle's compiled input would not necessarily notice.
    reads("steward-worker/src/main/java/eu/nordtal/s2/steward/worker/api/WorkerApi.java")

    // TopologyTest asks the release workflow whether it pushes every ghcr.io/nordtal image
    // compose.yml defaults to. It is a text read of the workflow, so Gradle has to be told.
    reads(".github/workflows/release.yml")

    // And StewardDeployer as text, for the one default that has to be the same on both sides of
    // the socket: the compose project name.
    reads("steward-deployer/src/main/java/eu/nordtal/s2/steward/deployer/StewardDeployer.java")

    // TopologyTest holds deploy/dev.env.example against every required variable in compose.yml:
    // compose interpolates the whole file before filtering by profile, so one unset `${X:?}` stops
    // the local stack even for a service it never starts.
    reads("deploy/dev.env.example")
}

repositories {
    maven("https://jitpack.io")
}

dependencies {
    // NullAway's annotations name checker-qual's TypeUseLocation; without it javac warns and -Werror fails.
    compileOnly("org.checkerframework:checker-qual:4.2.3")
    testCompileOnly("org.checkerframework:checker-qual:4.2.3")

    // The internal API steward-ui calls. It is here rather than in the interface because §3 keeps
    // the docker socket away from the web layer: the part an attacker reaches must not be the part
    // that can stop a container. Javalin brings jetty and slf4j and nothing else that matters -
    // its jackson and gson support are both `optional`, so the mapper is a choice, not a surprise.
    implementation(libs.javalin)

    // The config system and the Flyway migration. jcore exports gson and snakeyaml as api
    // dependencies, so nothing here declares a parser of its own: a second copy of gson on the
    // classpath is how you get two Gson types that are not each other.
    implementation(libs.jcore)

    // jcore exports no logging backend, and without one SLF4J binds to a no-op and every line this
    // module logs disappears.
    runtimeOnly(libs.logback.classic)

    // Compiled against in tests only, and for one reason: WorkerShutdownTest counts warnings. The
    // defect it holds does not fail anything - it logs, tens of thousands of times a second - so
    // the backend is what the assertion is made of.
    testImplementation(libs.logback.classic)

    // The bank (steward/109). This is the ONLY process in the network that holds a bunq credential
    // and the only one that makes an HTTP call to bunq: the bot writes a row asking for a tab and
    // reads back what happened, and has neither the key nor the SDK on its classpath any more.
    //
    // It brings OkHttp with it. `com/bunq/sdk/http/BunqRequestBuilder.java` in this module is a
    // patched copy of one of the SDK's own classes, sitting in the SDK's package so it wins on the
    // classpath - read that file before touching this line or the OkHttp version.
    implementation(libs.bunq.sdk)

    // Compiled against, not merely shipped: the patched BunqRequestBuilder above extends
    // okhttp3.Request.Builder, and the SDK's POM puts OkHttp at RUNTIME scope only - so without
    // this line the patch does not compile, which is how this was found. `implementation` and not
    // `compileOnly` on purpose: Gradle then resolves one version for both classpaths, so a bunq
    // bump that moves OkHttp can never leave this module compiling against one major and running
    // on another. See the comment beside `okhttp` in libs.versions.toml.
    implementation(libs.okhttp)

    // Compiled against, not just shipped: PostgresNotifications unwraps org.postgresql.PGConnection
    // to call getNotifications(int), the only way pgjdbc exposes LISTEN/NOTIFY. Declared here so the
    // version comes from this repo's catalog rather than from jcore's POM.
    implementation(libs.postgresql.driver)

    // Where the migration SQL lives: common/src/main/resources/db/migration, on this module's
    // classpath because :common is shaded into its jar. :common declares JDBI, HikariCP and slf4j
    // compileOnly, so this brings no stack of its own.
    implementation(project(":common"))

    // ServeLockIntegrationTest needs a real PostgreSQL: an advisory lock is a property of a database
    // session, with no in-JVM stand-in. It skips itself when no Docker daemon is reachable.
    testImplementation(libs.testcontainers.postgresql)

    // ConfigFilesOwnershipTest (steward/104) needs a file owned by somebody other than the test
    // process to prove the atomic write carries that ownership across - see the version comment
    // in libs.versions.toml for why an in-memory filesystem is the only place that scenario is
    // actually testable, on this host or in CI.
    testImplementation(libs.jimfs)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}
