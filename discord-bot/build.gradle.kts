plugins {
    id("nordtal.jvm-app")
}

application.mainClass.set("eu.nordtal.s2.discordbot.AccessBot")

// ConfigsTest reads the NORDTAL_ACCESS_LANGUAGES and NORDTAL_ACCESS_TIERS blocks out of the real
// .env.example instead of holding a copy of them. Without this the file is invisible to Gradle
// and editing it leaves :discord-bot:test UP-TO-DATE, which is the drift the test exists to catch.
repositoryRootTestInputs {
    // StartupFailuresTest reads AccessBot as text - the path it guards needs Discord to reject a
    // real token, which no build can arrange.
    reads("discord-bot/src/main/java/eu/nordtal/s2/discordbot/AccessBot.java")
    reads(".env.example")
}

repositories {
    maven("https://jitpack.io")
}

dependencies {
    // The only jcore dependency in this repo. It carries the commented-YAML config system
    // (eu.nordtal.jcore.config) and the eu.nordtal.jcore.persistence.sql layer
    // (JDBI 3 + HikariCP + Flyway) the bot is built on, and exports jdbi3-core,
    // jdbi3-sqlobject, slf4j-api, commons-lang3, commons-io, gson, snakeyaml and
    // org.jetbrains:annotations as transitive api dependencies.
    //
    // 3.0.0 removed JsonConfig / JsonConfigLoader and dropped jackson-databind entirely.
    // Nothing in this module used Jackson, so it is not declared here either.
    implementation(libs.jcore)

    // jcore stopped exporting a logging backend with 2.0.0 (it is testRuntimeOnly there), so the
    // bot has to bring its own. Without this SLF4J binds to a no-op and every log line vanishes
    // silently, with nothing but a one-line "no providers found" warning on stderr.
    runtimeOnly(libs.logback.classic)

    // jcore declares the driver runtimeOnly, which does reach us through its POM's runtime scope.
    // Declared here anyway so the version is pinned in this repo's catalog rather than inherited.
    runtimeOnly(libs.postgresql.driver)

    // The access API (eu.nordtal.s2.common.access) and the message system live here. :common
    // declares JDBI, HikariCP and slf4j compileOnly, so this brings no transitive stack of its
    // own - the bot already has all three at runtime through jcore, and pinning both to the same
    // versions in the catalog is what keeps it one copy of each rather than two.
    implementation(project(":common"))

    implementation(libs.jda)
    implementation(libs.bunq.sdk)

    // Flyway, to COMPILE against - not to ship. jcore declares it `implementation`, so it reaches
    // this jar at runtime through the POM's runtime scope but is invisible at compile time. The
    // bot needs the type for one call: SchemaCheck#validate, which is what is left after the
    // migration moved to :updater on 2026-09-01. The version is pinned to exactly jcore's, because
    // two Flyways on one classpath is a ServiceLoader registry with holes in it - which this
    // repository has already been bitten by once (see nordtal.shaded's mergeServiceFiles comment).
    compileOnly(libs.flyway.core)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    // The DAO layer is tested against a real PostgreSQL container - an in-memory stand-in would
    // not exercise gen_random_uuid(), the partial unique index or numeric(10,2) rounding.
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.postgresql.driver)
}
