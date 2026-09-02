plugins {
    id("nordtal.paper-plugin")
}

// ComposeWorldTest compares config.yml's world-name default against the level-name compose starts
// Paper on. compose.yml is in no source set, so without this an edit to it leaves :hunger-games:test
// UP-TO-DATE and the comparison never runs.
repositoryRootTestInputs {
    reads("compose.yml")
}

repositories {
    // jcore is published via JitPack, not Maven Central - see the workspace CLAUDE.md.
    maven("https://jitpack.io")
}

dependencies {
    // eu.nordtal.jcore.config is this repo's config system (season-2/CLAUDE.md, "Configuration"),
    // and this is the first Paper module to use it. jcore 3.0.0 also exports JDBI 3, HikariCP and
    // the PostgreSQL driver as part of its own dependency block - exactly the stack this plugin
    // needs anyway to read/write hg_game, hg_team, hg_member and hg_event and to resolve a
    // discord_id -> mc_uuid mapping through account_link. One dependency covers both needs, the
    // same way network-control/build.gradle.kts already does it - see that file's long comment for
    // why Flyway is excluded: this plugin never migrates anything (the bot owns the schema), and
    // flyway-core alone drags in ~1200 classes of Jackson 3 databind this jar has no use for.
    implementation(libs.jcore) {
        exclude(group = "org.flywaydb")
    }

    // Same reasoning as network-control's AccessPool: a HikariCP pool is built directly here so
    // connectionTimeout and the PostgreSQL driver's socketTimeout are actually tunable, which
    // AccessDirectory.open(String, String, String) does not expose. jcore only puts HikariCP on
    // the runtime classpath (declared implementation inside jcore, not api), so this module's own
    // code has to declare it too to compile against it. Pinned to jcore's own version via the
    // catalog, so exactly one copy resolves.
    implementation(libs.hikaricp)

    // HungerGamesDao installs JDBI's PostgresPlugin directly (the same way :common's
    // JdbiAccessDirectory and network-control's PlaytimeStore both do), which needs
    // org.jdbi.v3.postgres.PostgresPlugin on the compile classpath. jcore only declares
    // jdbi3-postgres `runtime` scope (verified against jcore's own POM), so this module's code has
    // to declare it too - pinned to jcore's own version via the catalog.
    implementation(libs.jdbi.postgres)
}
