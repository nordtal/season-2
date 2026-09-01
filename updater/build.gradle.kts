plugins {
    id("nordtal.jvm-app")
}

application.mainClass.set("eu.nordtal.s2.updater.UpdaterMain")

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

    // Not used yet - step 2 (Flyway) is what needs a live connection. Declared here so the version
    // is pinned in this repo's catalog rather than inherited from jcore's POM, exactly as
    // :discord-bot does it.
    runtimeOnly(libs.postgresql.driver)

    // Where the migration SQL lives: common/src/main/resources/db/migration. The files arrive on
    // this module's classpath because :common is shaded into its jar, which is exactly how they
    // used to arrive on the bot's - the SQL did not move when the Flyway call did.
    //
    // :common declares JDBI, HikariCP and slf4j compileOnly, so this brings no stack of its own:
    // all three are already here through jcore, at the versions the catalog pins.
    implementation(project(":common"))

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}
