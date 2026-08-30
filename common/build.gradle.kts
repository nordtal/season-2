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
    // These four - and only these four - are what a consumer of :common shades. jcore is
    // deliberately NOT used here even though it wraps exactly this stack: its dependency block
    // (JDA-adjacent config system, Flyway, commons-*, gson, snakeyaml) is what makes the bot's
    // jar ~33 MB, and a Paper plugin must not carry that. The versions are pinned to jcore's own
    // in gradle/libs.versions.toml, so the bot - which has both on its classpath - resolves one
    // copy of each.
    //
    // Nothing from these libraries appears on the public API of :common: the factories take a
    // javax.sql.DataSource or a JDBC URL, both JDK types. A consumer therefore never compiles
    // against JDBI itself.
    implementation(libs.jdbi.core)
    implementation(libs.jdbi.sqlobject)
    implementation(libs.jdbi.postgres)
    implementation(libs.hikaricp)
    implementation(libs.slf4j.api)

    // Loaded by service lookup, never referenced by name.
    runtimeOnly(libs.postgresql.driver)

    // The integration tests apply payments-bot's real Flyway migration against a real PostgreSQL
    // container (see AccessSchema in src/test/java). Flyway is a test dependency only - :common
    // never migrates anything at runtime; the bot owns that.
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.testcontainers.postgresql)
    // Compile scope, not runtimeOnly: the integration test builds a PGSimpleDataSource by hand so
    // that it can hand the same pool to Flyway, to AccessDirectory and to its own setup SQL.
    testImplementation(libs.postgresql.driver)
    testRuntimeOnly(libs.logback.classic)
}

// The access schema lives in payments-bot (it is the only module that migrates), but the API that
// reads it lives here. Rather than keeping a second copy of the DDL in test resources - which
// would drift the first time a column changes - the tests apply the migration directory itself.
//
// Referenced through rootProject.file rather than project(":payments-bot") so no cross-project
// model access happens at configuration time.
val accessMigrations: Directory = rootProject.layout.projectDirectory
    .dir("payments-bot/src/main/resources/db/migration")

tasks.named<Test>("test") {
    inputs.dir(accessMigrations).withPropertyName("accessMigrations").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("nordtal.test.migrations", accessMigrations.asFile.absolutePath)
}
