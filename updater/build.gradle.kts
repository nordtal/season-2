plugins {
    id("nordtal.jvm-app")
}

application.mainClass.set("eu.nordtal.s2.updater.UpdaterMain")

// TopologyTest reads the real compose.yml, for the same reason and with the same consequence:
// a compose file edited on its own would otherwise leave :updater:test UP-TO-DATE.
repositoryRootTestInputs {
    reads("compose.yml")

    // DocumentedCommandsTest reads every file that writes `docker compose run --rm updater` down
    // for a person to copy. The bug it guards was in the documents, not in the dispatch, so the
    // documents are what has to be an input - otherwise editing one leaves :updater:test UP-TO-DATE.
    reads(".env.example")
    reads("updater/Dockerfile")
    reads("updater/README.md")
    reads("docs/updater.md")
    reads("deploy/README.md")
}

repositories {
    maven("https://jitpack.io")
}

dependencies {
    // The config system (eu.nordtal.jcore.config) and - from step 2 of docs/updater.md onwards -
    // the Flyway migration this module takes over from the bot. jcore exports gson and snakeyaml
    // as api dependencies, which is where this module's JSON and YAML parsing comes from: nothing
    // here declares a parser of its own, because a second copy of gson on the classpath is how
    // you get two Gson types that are not each other.
    implementation(libs.jcore)

    // jcore stopped exporting a logging backend with 2.0.0. Without this SLF4J binds to a no-op
    // and every line this module logs disappears, which for a process whose entire output is a
    // report would be the whole program.
    runtimeOnly(libs.logback.classic)

    // Compiled against, not just shipped: PostgresNotifications unwraps org.postgresql.PGConnection
    // to call getNotifications(int), which is the only way pgjdbc exposes LISTEN/NOTIFY - it has no
    // callback API, so a thread sits on that call. network-control depends on it for exactly the
    // same reason and says so at length. Declared here so the version is pinned in this repo's
    // catalog rather than inherited from jcore's POM, as :discord-bot does it.
    implementation(libs.postgresql.driver)

    // Where the migration SQL lives: common/src/main/resources/db/migration. The files arrive on
    // this module's classpath because :common is shaded into its jar, which is exactly how they
    // used to arrive on the bot's - the SQL did not move when the Flyway call did.
    //
    // :common declares JDBI, HikariCP and slf4j compileOnly, so this brings no stack of its own:
    // all three are already here through jcore, at the versions the catalog pins.
    implementation(project(":common"))

    // ServeLockIntegrationTest needs a real PostgreSQL: an advisory lock is a property of a
    // database session, and there is no in-JVM stand-in for "a second connection is refused". It
    // skips itself when no Docker daemon is reachable, like every other integration test here.
    testImplementation(libs.testcontainers.postgresql)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}
