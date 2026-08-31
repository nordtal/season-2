plugins {
    id("nordtal.java-base")
}

dependencies {
    // Adventure comes from paper-api / velocity-api at runtime on both platforms,
    // so it is compile-only here and never shaded.
    compileOnly(libs.adventure.api)
    compileOnly(libs.adventure.minimessage)
    compileOnly(libs.annotations)

    // The access API (eu.nordtal.s2.common.access) talks to PostgreSQL directly, because the
    // database is the source of truth for access and the proxy has to read it on the login path.
    //
    // They are compileOnly ON PURPOSE. Declared as implementation they would be shaded into every
    // consumer of :common, including hunger-games and resource-pack-coercion, which never touch a
    // database - 3.12 MB per plugin jar instead of 20 KB.
    //
    // THAT 3.12 MB IS A COUNTERFACTUAL, not a measurement of the jars this build produces. It is
    // what they WOULD weigh with these declarations changed. What they actually weigh, rebuilt
    // 2026-08-31: smp 34,745 B, hunger-games 34,784 B, resource-pack-coercion 34,886 B - because
    // none of the three has opted into libs.bundles.access-persistence yet. network-control is
    // 5,196,184 B because it did (through jcore). The distinction was lost when this number was
    // copied into CLAUDE.md and docs/state-of-play.md as if it described the current jars; do not
    // quote it as one. A module that actually uses
    // eu.nordtal.s2.common.access opts in with `implementation(libs.bundles.access.persistence)`
    // plus `runtimeOnly(libs.postgresql.driver)`; one that forgets fails with a
    // NoClassDefFoundError the first time it calls the access API. That is the accepted trade.
    //
    // jcore is deliberately NOT used here even though it wraps exactly this stack: its dependency
    // block (config system, Flyway, commons-*, gson, snakeyaml) is what makes the bot's jar ~33 MB.
    // The versions are pinned to jcore's own in gradle/libs.versions.toml, so the bot - which has
    // both on its classpath - resolves one copy of each.
    //
    // Nothing from these libraries appears on the public API of :common: the factories take a
    // javax.sql.DataSource or a JDBC URL, both JDK types. A consumer therefore never compiles
    // against JDBI itself.
    compileOnly(libs.jdbi.core)
    compileOnly(libs.jdbi.sqlobject)
    compileOnly(libs.jdbi.postgres)
    compileOnly(libs.hikaricp)
    compileOnly(libs.slf4j.api)

    // The integration tests apply this module's own Flyway migration - src/main/resources/db/
    // migration, on the test runtime classpath - against a real PostgreSQL container (see
    // AccessSchema in src/test/java). Flyway is a test dependency only: :common never migrates
    // anything at runtime, the bot owns that, and Flyway must never reach a plugin jar.
    // :common's own tests exercise the access API, so they need the runtime stack that consumers
    // otherwise bring themselves.
    testImplementation(libs.bundles.access.persistence)

    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.testcontainers.postgresql)
    // Compile scope, not runtimeOnly: the integration test builds a PGSimpleDataSource by hand so
    // that it can hand the same pool to Flyway, to AccessDirectory and to its own setup SQL.
    testImplementation(libs.postgresql.driver)
    testRuntimeOnly(libs.logback.classic)
}

// The migration files moved here from access-bot on 2026-08-31 (docs/architecture.md#schema-
// ownership): the DDL now sits next to the API that reads it, and the tests pick it up off their
// own runtime classpath. That replaced a `nordtal.test.migrations` system property pointing into
// the bot module - no build wiring is needed for it any more.
