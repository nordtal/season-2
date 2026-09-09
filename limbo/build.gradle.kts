plugins {
    id("nordtal.paper-plugin")
}

repositories {
    // jcore is published via JitPack, not Maven Central.
    maven("https://jitpack.io")
}

dependencies {
    implementation(project(":paper-common"))
    // jcore carries the config system and exports the JDBI 3 / HikariCP / PostgreSQL stack
    // :common's AccessDirectory needs.
    //
    // WHY A WAITING ROOM HAS A DATABASE AT ALL, since it holds nobody's state: its entire interface
    // is one translated title, and a plugin reads a player's language from the database at join
    // through :common's PlayerLocales.
    //
    // Flyway is excluded because this plugin never migrates anything (the bot owns the schema) and
    // flyway-core alone drags in ~1200 classes of Jackson databind. Excluding the group removes the
    // subtree from resolution rather than only from the final jar.
    implementation(libs.jcore) {
        exclude(group = "org.flywaydb")
    }

    // LimboPool builds a HikariCP pool directly so the pool name, size and driver class are ours -
    // AccessDirectory.open(String, String, String) exposes none of that. jcore only puts HikariCP
    // on the runtime classpath, so this module declares it to compile against it; the catalog pins
    // it to jcore's version so exactly one copy resolves.
    implementation(libs.hikaricp)

    // :common's JdbiAccessDirectory installs JDBI's PostgresPlugin, and jcore declares
    // jdbi3-postgres at runtime scope only. Declared here so it is visible in this module's own
    // dependency list rather than arriving through somebody else's POM.
    implementation(libs.jdbi.postgres)
}
