plugins {
    id("nordtal.velocity-plugin")
}

// BrandColourTest reads NetworkSpec's own source, so Gradle has to see that file as a test input -
// otherwise editing the spec leaves :network-control:test UP-TO-DATE.
repositoryRootTestInputs {
    reads("network-control/src/main/java/eu/nordtal/s2/networkcontrol/config/NetworkSpec.java")
}

repositories {
    // jcore only.
    maven("https://jitpack.io")
}

dependencies {
    // :commands carries the declarations, decisions and message keys; this module is the adapter.
    implementation(project(":commands"))

    // jcore carries eu.nordtal.jcore.config (database.yml and gate.yml) and also exports JDBI 3,
    // HikariCP and the PostgreSQL driver, which is the stack AccessDirectory needs.
    //
    // Flyway is excluded here rather than at shadowJar time: this module never migrates anything
    // (access-bot owns the schema), and a shadowJar-level exclude would only drop the two named
    // artifacts' own classes, leaving flyway-database-cockroachdb and the whole Jackson databind
    // stack that flyway-core pulls in. Excluding the group removes the subtree from resolution.
    implementation(libs.jcore) {
        exclude(group = "org.flywaydb")
    }

    // AccessPool builds a HikariCP pool directly, and jcore exposes HikariCP only at runtime. The
    // catalog pins it to jcore's own version so there is exactly one copy on the classpath.
    implementation(libs.hikaricp)

    // jcore already brings both at runtime; these put them on the compile classpath for two
    // classes: PostgresPhaseNotifications unwraps org.postgresql.PGConnection to poll
    // getNotifications(timeout), because pgjdbc has no callback API and LISTEN cannot be written
    // against java.sql alone; PlaytimeStore installs jdbi3-core's PostgresPlugin.
    implementation(libs.jdbi.postgres)
    implementation(libs.postgresql.driver)

    // velocity-api is compileOnly, so it is not on the test classpath by default. The tests need
    // it for Adventure's Component and nothing more; none of them starts a proxy.
    testImplementation(libs.velocity.api)

    // PlaytimeDao's upsert is the one SQL statement this module owns, and no in-memory test can
    // say anything about it: the integration test runs the real migrations against a PostgreSQL
    // container and skips itself when no Docker daemon is reachable. Test-only; Flyway never
    // reaches the shaded jar.
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.testcontainers.postgresql)
}
