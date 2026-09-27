import eu.nordtal.s2.build.GenerateGlyphAdvances

plugins {
    id("nordtal.java-base")
}

// The plugins size pills and menu rows from these glyph advance tables, derived from the assembled pack on
// every build and never committed.
val assembledPack = configurations.resolvable("packForAdvances") { extendsFrom(configurations["resourcePack"]) }

val bossbarAdvances =
    tasks.register<GenerateGlyphAdvances>("generateBossbarAdvances") {
        pack.from(assembledPack)
        font.set("nordtal/font/bossbar.json")
        target.set(layout.buildDirectory.file("generated/advances/nordtal/hud/bossbar-advances.properties"))
    }

val menuAdvances =
    tasks.register<GenerateGlyphAdvances>("generateMenuAdvances") {
        pack.from(assembledPack)
        font.set("nordtal/font/gui_r0.json")
        target.set(layout.buildDirectory.file("generated/advances/nordtal/menu/gui-row-advances.properties"))
    }

sourceSets.main {
    resources.srcDir(files(layout.buildDirectory.dir("generated/advances")).builtBy(bossbarAdvances, menuAdvances))
}

// Files outside this module that :common's tests read, so an edit to one reruns :common:test.
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

    // Every message bundle, for EveryBundleIsCompleteTest: Gradle cannot see the test's own tree walk.
    reads("commands/src/main/resources/messages/commands/en.properties")
    reads("commands/src/main/resources/messages/commands/de.properties")
    reads("discord-bot/src/main/resources/messages/access/en.properties")
    reads("discord-bot/src/main/resources/messages/access/de.properties")
    reads("hunger-games/src/main/resources/messages/hunger-games/en.properties")
    reads("hunger-games/src/main/resources/messages/hunger-games/de.properties")
    reads("limbo/src/main/resources/messages/limbo/en.properties")
    reads("limbo/src/main/resources/messages/limbo/de.properties")
    reads("proxy/src/main/resources/messages/proxy/en.properties")
    reads("proxy/src/main/resources/messages/proxy/de.properties")
    reads("paper-common/src/main/resources/messages/paper-common/en.properties")
    reads("paper-common/src/main/resources/messages/paper-common/de.properties")
    reads("smp/src/main/resources/messages/smp/en.properties")
    reads("smp/src/main/resources/messages/smp/de.properties")

    reads("smp/src/main/java/eu/nordtal/s2/smp/hud/SmpHud.java")
    reads("hunger-games/src/main/java/eu/nordtal/s2/hungergames/hud/HudRenderer.java")

    reads("smp/src/main/resources/messages/smp/en.properties")
    reads("smp/src/main/resources/messages/smp/de.properties")
    reads("hunger-games/src/main/resources/messages/hunger-games/en.properties")
    reads("hunger-games/src/main/resources/messages/hunger-games/de.properties")

    reads("limbo/src/main/resources/messages/limbo/en.properties")
    reads("limbo/src/main/resources/messages/limbo/de.properties")

    readsTree("smp/src/main")
    readsTree("limbo/src/main")
    readsTree("hunger-games/src/main")
    readsTree("proxy/src/main")

    readsTree("commands/src/main")
    readsTree("paper-common/src/main")

    readsTree("discord-bot/src/main/resources/messages")

    // The config packages ConfigSpecExplanationTest walks that no tree above covers.
    readsTree("discord-bot/src/main/java/eu/nordtal/s2/discordbot/config")
    readsTree("steward-ui/src/main/java/eu/nordtal/s2/steward/ui/config")
    readsTree("steward-worker/src/main/java/eu/nordtal/s2/steward/worker/config")
}

dependencies {
    // The assembled pack, which the tests read through the nordtal.pack system property.
    "resourcePack"(project(":resource-pack", "pack"))

    // NullAway's annotations reference checker-qual at class-file level; this is the version NullAway pulls in.
    compileOnly("org.checkerframework:checker-qual:4.2.3")
    testCompileOnly("org.checkerframework:checker-qual:4.2.3")

    // Adventure comes from the platform at runtime and is never shaded.
    compileOnly(libs.adventure.api)
    compileOnly(libs.adventure.minimessage)

    testImplementation(libs.adventure.api)
    testImplementation(libs.adventure.minimessage)

    testImplementation(libs.gson)

    // compileOnly, so no consumer shades the database stack; one that needs it adds libs.bundles.access.persistence.
    compileOnly(libs.jdbi.core)
    compileOnly(libs.jdbi.sqlobject)
    compileOnly(libs.jdbi.postgres)
    compileOnly(libs.hikaricp)
    compileOnly(libs.slf4j.api)

    // The notify package unwraps PGConnection, because pgjdbc has no callback API for LISTEN.
    compileOnly(libs.postgresql.driver)

    // Test-only: :common never migrates, and Flyway must never reach a plugin jar.
    testImplementation(libs.bundles.access.persistence)

    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.testcontainers.postgresql)
    // Compile scope: the integration tests build a PGSimpleDataSource by hand.
    testImplementation(libs.postgresql.driver)
    testRuntimeOnly(libs.logback.classic)
}
