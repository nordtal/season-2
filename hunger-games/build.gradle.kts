import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("nordtal.paper-plugin")
    id("nordtal.message-spec")
}

// Names start() and its field-assigning helpers as NullAway's initializers, since HungerGamesPlugin's fields are
// set there, not in a constructor.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        option(
            "NullAway:KnownInitializers",
            "eu.nordtal.s2.hungergames.HungerGamesPlugin.start," +
                "eu.nordtal.s2.hungergames.HungerGamesPlugin.loadConfigAndMessages," +
                "eu.nordtal.s2.hungergames.HungerGamesPlugin.wireGameSystems," +
                "eu.nordtal.s2.hungergames.HungerGamesPlugin.wireAdminWatchAndCommands," +
                "eu.nordtal.s2.hungergames.HungerGamesPlugin.wireCommandFilterAndAdminWatch," +
                "eu.nordtal.s2.hungergames.HungerGamesPlugin.refreshCurrentGame," +
                "eu.nordtal.s2.hungergames.HungerGamesPlugin.startHeartbeat",
        )
    }
}

// Tests here read files that are in no source set, or read source rather than bytecode. Without
// declaring them as inputs, editing one leaves :hunger-games:test UP-TO-DATE and the check never
// runs.
repositoryRootTestInputs {
    reads("compose.yml")
    reads("hunger-games/src/main/java/eu/nordtal/s2/hungergames/command/HungerGamesCommand.java")
    reads("hunger-games/src/main/java/eu/nordtal/s2/hungergames/listener/CombatListener.java")
    reads("hunger-games/src/main/resources/messages/hunger-games/en.properties")
    reads("hunger-games/src/main/resources/messages/hunger-games/de.properties")
}

repositories {
    // jcore is published via JitPack, not Maven Central.
    maven("https://jitpack.io")
}

dependencies {
    implementation(project(":paper-common"))
    // jcore brings the config system plus JDBI 3, HikariCP and the PostgreSQL driver. Flyway is
    // excluded because this plugin never migrates anything (the bot owns the schema) and
    // flyway-core drags in Jackson 3 databind.
    implementation(libs.jcore) {
        exclude(group = "org.flywaydb")
    }

    // The pool is built directly here so connectionTimeout and the driver's socketTimeout stay
    // tunable. jcore declares HikariCP `implementation`, not `api`, so it must be redeclared to
    // compile against it. Pinned to jcore's version via the catalog so exactly one copy resolves.
    implementation(libs.hikaricp)

    // HungerGamesDao installs JDBI's PostgresPlugin, which jcore only declares at runtime scope.
    implementation(libs.jdbi.postgres)

    // KillCountsIntegrationTest drives HungerGamesDao#killCounts against a real PostgreSQL running
    // the real migrations: the method is SQL plus JDBI column mapping, and count(*) is bigint
    // against a Map<UUID, Integer> return - nothing in-memory can catch that.
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.testcontainers.postgresql)

    // jcore puts the driver on the runtime classpath only; the test that builds a
    // PGSimpleDataSource by hand needs it at compile time.
    testImplementation(libs.postgresql.driver)
}

messageSpec {
    specClass.set("eu.nordtal.s2.hungergames.HungerGamesMessages")
}
