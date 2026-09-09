plugins {
    id("nordtal.java-base")
}

// Files outside this module's source sets that :common's tests read as text. Without these
// declarations an edit to one of them leaves :common:test UP-TO-DATE and the drift goes unnoticed.
repositoryRootTestInputs {
    reads("resource-pack/src/pack.mcmeta")
    reads("smp/src/main/resources/paper-plugin.yml")
    reads("limbo/src/main/resources/paper-plugin.yml")
    reads("hunger-games/src/main/resources/paper-plugin.yml")

    reads("smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java")
    reads("limbo/src/main/java/eu/nordtal/s2/limbo/LimboPlugin.java")
    reads("hunger-games/src/main/java/eu/nordtal/s2/hungergames/HungerGamesPlugin.java")

    reads("discord-bot/src/main/java/eu/nordtal/s2/discordbot/AccessBot.java")

    reads("deploy/minecraft/entrypoint.sh")

    reads("gradle/libs.versions.toml")

    reads("smp/src/main/java/eu/nordtal/s2/smp/hud/SmpHud.java")
    reads("hunger-games/src/main/java/eu/nordtal/s2/hungergames/hud/HudRenderer.java")
    reads("resource-pack/src/assets/nordtal/font/bossbar.json")
    reads("resource-pack/src/assets/nordtal/font/board.json")

    // Whole trees rather than file lists: a list goes stale the first time somebody adds a file,
    // and that new file is exactly what the rules are about.
    readsTree("resource-pack/src/assets")

    reads("smp/src/main/resources/messages/smp/en.properties")
    reads("smp/src/main/resources/messages/smp/de.properties")
    reads("hunger-games/src/main/resources/messages/hunger-games/en.properties")
    reads("hunger-games/src/main/resources/messages/hunger-games/de.properties")

    reads("limbo/src/main/resources/messages/limbo/en.properties")
    reads("limbo/src/main/resources/messages/limbo/de.properties")

    readsTree("smp/src/main")
    readsTree("limbo/src/main")
    readsTree("hunger-games/src/main")
    readsTree("network-control/src/main")

    readsTree("commands/src/main")
    readsTree("paper-common/src/main")

    readsTree("discord-bot/src/main/resources/messages")
}

dependencies {
    // Adventure comes from paper-api / velocity-api at runtime on both platforms,
    // so it is compile-only here and never shaded.
    compileOnly(libs.adventure.api)
    compileOnly(libs.adventure.minimessage)

    testImplementation(libs.adventure.api)
    testImplementation(libs.adventure.minimessage)

    testImplementation(libs.gson)

    compileOnly(libs.annotations)

    // The access API (eu.nordtal.s2.common.access) talks to PostgreSQL directly, because the
    // database is the source of truth for access and the proxy has to read it on the login path.
    //
    // These are compileOnly ON PURPOSE: as `implementation` they would be shaded into every
    // consumer of :common, including hunger-games and limbo, which never touch a database. A module
    // that actually uses the access API opts in with `implementation(libs.bundles.access.persistence)`
    // plus `runtimeOnly(libs.postgresql.driver)`; one that forgets fails with a NoClassDefFoundError
    // the first time it calls that API. That is the accepted trade.
    //
    // jcore is deliberately not used here even though it wraps the same stack: its dependency block
    // (config system, Flyway, commons-*, gson, snakeyaml) is far heavier. The versions are pinned to
    // jcore's own in gradle/libs.versions.toml so the bot, which has both on its classpath, resolves
    // one copy of each.
    //
    // Nothing from these libraries appears on :common's public API - the factories take a
    // javax.sql.DataSource or a JDBC URL, both JDK types.
    compileOnly(libs.jdbi.core)
    compileOnly(libs.jdbi.sqlobject)
    compileOnly(libs.jdbi.postgres)
    compileOnly(libs.hikaricp)
    compileOnly(libs.slf4j.api)

    // eu.nordtal.s2.common.notify unwraps org.postgresql.PGConnection to call
    // getNotifications(timeout): pgjdbc has no callback API, so a LISTEN loop cannot be written
    // against java.sql alone.
    compileOnly(libs.postgresql.driver)

    // Flyway is a test dependency only: :common never migrates anything at runtime, the bot owns
    // that, and Flyway must never reach a plugin jar.
    testImplementation(libs.bundles.access.persistence)

    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.testcontainers.postgresql)
    // Compile scope, not runtimeOnly: the integration test builds a PGSimpleDataSource by hand so
    // that it can hand the same pool to Flyway, to AccessDirectory and to its own setup SQL.
    testImplementation(libs.postgresql.driver)
    testRuntimeOnly(libs.logback.classic)
}
