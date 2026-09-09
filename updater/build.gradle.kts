plugins {
    id("nordtal.jvm-app")
}

application.mainClass.set("eu.nordtal.s2.updater.UpdaterMain")

// TopologyTest reads the real compose.yml; without declaring it, editing that file alone would
// leave :updater:test UP-TO-DATE.
repositoryRootTestInputs {
    reads("compose.yml")

    // DocumentedCommandsTest reads every file that writes `docker compose run --rm updater` down for
    // a person to copy, so those documents have to be inputs too.
    reads(".env.example")
    reads("updater/Dockerfile")
    reads("updater/README.md")
    reads("deploy/README.md")

    // CountdownComesAfterResolvingTest reads Runner as text, because what it asserts is the order of
    // two statements inside one method and Gradle's own input is the compiled class.
    reads("updater/src/main/java/eu/nordtal/s2/updater/serve/Runner.java")

    // TopologyTest holds deploy/dev.env.example against every required variable in compose.yml:
    // compose interpolates the whole file before filtering by profile, so one unset `${X:?}` stops
    // the local stack even for a service it never starts.
    reads("deploy/dev.env.example")
}

repositories {
    maven("https://jitpack.io")
}

dependencies {
    // The config system and the Flyway migration. jcore exports gson and snakeyaml as api
    // dependencies, so nothing here declares a parser of its own: a second copy of gson on the
    // classpath is how you get two Gson types that are not each other.
    implementation(libs.jcore)

    // jcore exports no logging backend, and without one SLF4J binds to a no-op and every line this
    // module logs disappears.
    runtimeOnly(libs.logback.classic)

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

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}
