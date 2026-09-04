plugins {
    id("nordtal.paper-plugin")
}

repositories {
    // jcore is published via JitPack, not Maven Central - see the workspace CLAUDE.md.
    maven("https://jitpack.io")
}

dependencies {
    implementation(project(":paper-common"))
    // The same block hunger-games has, and for the same two reasons: eu.nordtal.jcore.config is
    // this repository's config system, and jcore 3.0.0 happens to export exactly the JDBI 3 /
    // HikariCP / PostgreSQL stack :common's AccessDirectory needs to open a connection of its own.
    //
    // WHY A WAITING ROOM HAS A DATABASE AT ALL, since it holds nobody's state: its entire interface
    // is one translated title, and docs/i18n.md settles that a plugin reads a player's language from
    // the database at join through :common's PlayerLocales. The alternative - the proxy sending the
    // language in a plugin message - was rejected there on its own merits, and this module is not
    // the place to reopen it. Settled 2026-09-01; docs/architecture.md's old guess that limbo
    // "probably does not" need persistence was the loser of that contradiction.
    //
    // Flyway is excluded for the reason network-control's build file spells out at length: this
    // plugin never migrates anything (the bot owns the schema), and flyway-core alone drags in
    // ~1200 classes of Jackson 3 databind. Excluding the group removes the subtree from resolution
    // rather than only from the final jar.
    implementation(libs.jcore) {
        exclude(group = "org.flywaydb")
    }

    // LimboPool builds a HikariCP pool directly so the pool name, size and the driver class are
    // ours - AccessDirectory.open(String, String, String) exposes none of that. jcore only puts
    // HikariCP on the runtime classpath (declared implementation inside jcore, not api), so this
    // module has to declare it to compile against it. Pinned to jcore's own version via the
    // catalog, so exactly one copy resolves.
    implementation(libs.hikaricp)

    // :common's JdbiAccessDirectory installs JDBI's PostgresPlugin, and jcore declares
    // jdbi3-postgres in `runtime` scope only. Declared here for the same reason hunger-games
    // declares it: so the artifact is visible in this module's own dependency list rather than
    // arriving by accident through somebody else's POM.
    implementation(libs.jdbi.postgres)
}
