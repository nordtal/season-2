plugins {
    id("nordtal.jvm-app")
}

application.mainClass.set("eu.nordtal.s2.paymentsbot.NordTalPayments")

repositories {
    maven("https://jitpack.io")
}

dependencies {
    // The only jcore dependency in this repo. It carries the JSON config loader and the
    // eu.nordtal.jcore.persistence.sql layer (JDBI 3 + HikariCP + Flyway) the bot is built on,
    // and exports jdbi3-core, jdbi3-sqlobject, slf4j-api, commons-lang3, commons-io,
    // jackson-databind and org.jetbrains:annotations as transitive api dependencies.
    implementation(libs.jcore)

    // jcore stopped exporting a logging backend with 2.0.0 (it is testRuntimeOnly there), so the
    // bot has to bring its own. Without this SLF4J binds to a no-op and every log line vanishes
    // silently, with nothing but a one-line "no providers found" warning on stderr.
    runtimeOnly(libs.logback.classic)

    // jcore declares the driver runtimeOnly, which does reach us through its POM's runtime scope.
    // Declared here anyway so the version is pinned in this repo's catalog rather than inherited.
    runtimeOnly(libs.postgresql.driver)

    implementation(libs.jda)
    implementation(libs.bunq.sdk)
    implementation(libs.guava)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    // The DAO layer is tested against a real PostgreSQL container - an in-memory stand-in would
    // not exercise gen_random_uuid(), the partial unique index or numeric(10,2) rounding.
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.postgresql.driver)
}
