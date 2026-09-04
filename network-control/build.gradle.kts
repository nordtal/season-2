plugins {
    id("nordtal.velocity-plugin")
}

repositories {
    // jcore only. `app.simplecloud.api:api` used to be declared here as a compileOnly placeholder
    // for routing, together with repo.simplecloud.app/snapshots and buf.build/gen/maven for its
    // protobuf stubs. Routing was written on 2026-08-31 and imported none of it: the `routing`
    // package resolves backends by the names in gate.yml through Velocity's own
    // ProxyServer.getServer(name), which is exactly the fallback that had been written
    // down for the case where the SimpleCloud coordinate turned out not to resolve. Four fixed
    // servers lose nothing by being named instead of discovered, so the dependency, its two
    // repositories and its version-catalog entry were all removed on 2026-09-01 rather than left
    // as a build file implying a SimpleCloud integration that does not exist.
    maven("https://jitpack.io")
}

dependencies {
    // Every command this process answers that also exists somewhere else. :commands carries the
    // declaration, the decisions and the message keys; this module carries the adapter. It brings
    // :common with it (declared `api` there), which the convention plugin already adds.
    implementation(project(":commands"))

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

    // jcore brings both of these at runtime already (jdbi3-postgres and the driver are declared
    // `implementation` / `runtimeOnly` inside jcore, not `api`), so this adds no second copy of
    // anything - it puts them on THIS module's compile classpath, which two classes now need:
    //
    //   - PostgresPhaseNotifications unwraps org.postgresql.PGConnection to call
    //     getNotifications(timeout). docs/season-phases.md states plainly that the pgjdbc driver
    //     has no callback API, so the LISTEN half of the phase model cannot be written against
    //     java.sql alone.
    //   - PlaytimeStore installs jdbi3-core's PostgresPlugin, the same way :common's directories do.
    //
    // Versions come from the catalog, which pins them to exactly what jcore depends on.
    implementation(libs.jdbi.postgres)
    implementation(libs.postgresql.driver)

    // velocity-api is compileOnly (the convention plugin declares it, plus the annotation
    // processor that writes velocity-plugin.json), so it is not on the test classpath by default.
    // The tests need it for Adventure's Component - the fail-closed screen is one - and nothing
    // more; no test here starts a proxy, and none can.
    testImplementation(libs.velocity.api)

    // PlaytimeDao's `seconds = seconds + EXCLUDED.seconds` is the one statement this module owns,
    // and it is exactly the kind of SQL no in-memory test can say anything about. Same shape as
    // :common's integration tests: a real PostgreSQL container running the real migrations off the
    // classpath (:common is an `implementation` dependency, so db/migration is already there), and
    // the test skips itself when no Docker daemon is reachable. Flyway is test-only and never
    // reaches the shaded jar - network-control does not migrate anything, the bot does.
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.testcontainers.postgresql)
}
