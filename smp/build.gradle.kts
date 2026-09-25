plugins {
    id("nordtal.paper-plugin")
    id("nordtal.message-spec")
}

// Files outside every source set that tests read directly. Without declaring them Gradle cannot
// see them, and an edit to one would leave :smp:test UP-TO-DATE.
repositoryRootTestInputs {
    // TravelPanelTest asserts the panel's PNG and gui.json agree with TravelPanel's geometry.
    readsTree("resource-pack/src/assets")
    reads("compose.yml")
    reads("deploy/minecraft/entrypoint.sh")

    // SmpCommandWiringTest reads these as text: Gradle's test input is the compiled class, so a
    // change with identical bytecode would otherwise skip the check that the command inbox starts.
    reads("smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java")
    reads("smp/src/main/java/eu/nordtal/s2/smp/command/SmpCommand.java")

    // NpcSurvivesTest and SpawnNpcLabelTest read these as text, for the same reason.
    reads("smp/src/main/java/eu/nordtal/s2/smp/npc/SpawnNpc.java")
    reads("smp/src/main/java/eu/nordtal/s2/smp/npc/NpcProtection.java")

    // SeasonWelcomeIsWiredTest pins an ORDER: the season's opening moment is called from the
    // callback that waits for the player's language, not from the join handler above it.
    reads("smp/src/main/java/eu/nordtal/s2/smp/player/PresenceListener.java")
    reads("smp/src/main/java/eu/nordtal/s2/smp/welcome/SeasonWelcome.java")
}

repositories {
    // jcore and papermc-display-tags are both published via JitPack, not Maven Central.
    maven("https://jitpack.io")
}

dependencies {
    implementation(project(":paper-common"))
    // jcore carries this repository's config system and the JDBI 3 / HikariCP / PostgreSQL stack.
    // Flyway is excluded: this plugin never migrates anything (the bot owns the schema), and
    // flyway-core alone drags in ~1200 classes of Jackson 3 databind.
    implementation(libs.jcore) {
        exclude(group = "org.flywaydb")
    }

    // SmpPool builds a HikariCP pool directly so the pool name, size and both timeouts are ours.
    implementation(libs.hikaricp)

    // JDBI's PostgresPlugin, which :common's directories and this module's own DAO install. jcore
    // declares jdbi3-postgres in `runtime` scope only.
    implementation(libs.jdbi.postgres)

    // Nametags. compileOnly AND NEVER SHADED: the api module is an interface over the running
    // DisplayTags plugin, so a bundled copy would be a second set of classes for the same
    // interfaces and the lookup would hand back an instance of the wrong one. DisplayTags and
    // PacketEvents are therefore required at runtime on the SMP server.
    compileOnly(libs.display.tags)

    // SpinRefundIntegrationTest drives the wheel-refund statements against a real PostgreSQL: what
    // can go wrong in a refund is a property of the database, not of Java (a null `last_free` needs
    // an explicit cast, the free refund must be idempotent, the earned one must stay inside
    // smp_spin_used_not_negative). It skips itself when no Docker daemon is reachable.
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.testcontainers.postgresql)

    // The driver arrives at runtime only; the test builds a PGSimpleDataSource by hand and so has
    // to ask for it by name.
    testImplementation(libs.postgresql.driver)
}

messageSpec {
    specClass.set("eu.nordtal.s2.smp.SmpMessages")
}
