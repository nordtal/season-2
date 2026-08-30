plugins {
    id("nordtal.velocity-plugin")
}

repositories {
    maven("https://repo.simplecloud.app/snapshots")
    maven("https://buf.build/gen/maven")
    maven("https://jitpack.io")
}

dependencies {
    // Provided at runtime by the simplecloud-api platform plugin. Shading it causes
    // class-loading conflicts, so it must stay compileOnly.
    compileOnly(libs.simplecloud.api)

    // eu.nordtal.jcore.config is the config system used everywhere in this repo (see the module
    // CLAUDE.md, "Configuration") - database.yml and gate.yml are both loaded through it. jcore
    // 3.0.0 also exports JDBI 3 (api) and HikariCP + the PostgreSQL driver (implementation /
    // runtimeOnly) as part of its own dependency block, which happens to be exactly the stack
    // :common's AccessDirectory needs to open its own connection pool - so this one dependency
    // covers both the login gate's config and its persistence, rather than a second, separately
    // pinned copy of JDBI/HikariCP living alongside it.
    //
    // Flyway is excluded here rather than at shadowJar time. network-control never migrates
    // anything - access-bot owns the schema (see docs/access-system.md) - and Flyway is only ever
    // touched by jcore's eu.nordtal.jcore.persistence.sql.Database, which this module does not use
    // (AccessDirectory wires its own JDBI independently of jcore's persistence layer). A
    // shadowJar-level dependency() exclude only drops the two named Flyway artifacts' own class
    // files, not what THEY pull in - flyway-database-postgresql drags in
    // flyway-database-cockroachdb transitively, and flyway-core drags in the whole Jackson 3
    // databind stack (~1200 .class files) for its own config parsing. Excluding the group here
    // removes the entire subtree from resolution, not just from the final jar - caught by
    // inspecting `./gradlew :network-control:dependencies` and the shaded jar's contents by hand
    // on 2026-08-30, not assumed.
    implementation(libs.jcore) {
        exclude(group = "org.flywaydb")
    }

    // AccessPool builds a HikariCP pool directly (connectionTimeout, the PostgreSQL driver's own
    // socketTimeout - see AccessPool and DatabaseSpec#queryTimeoutSeconds), so this module's own
    // code has to compile against it. jcore only exposes HikariCP on the runtime classpath
    // (declared `implementation` inside jcore itself, not `api`), so it is declared here too -
    // pinned to jcore's own version in the catalog, which is what keeps exactly one copy on the
    // classpath rather than two.
    implementation(libs.hikaricp)
}
