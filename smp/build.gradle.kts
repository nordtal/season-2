import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("nordtal.paper-plugin")
    id("nordtal.message-spec")
}

// SmpPlugin assigns its fields in start(), called from onEnable, so NullAway checks that instead of the constructor.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        option("NullAway:KnownInitializers", "eu.nordtal.s2.smp.SmpPlugin.start")
    }
}

// Files outside every source set that tests read; undeclared, an edit would leave :smp:test UP-TO-DATE.
repositoryRootTestInputs {
    readsTree("resource-pack/src/assets")
    reads("compose.yml")
    reads("deploy/minecraft/entrypoint.sh")

    // Wiring tests read these sources as text, which identical bytecode would not rerun.
    reads("smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java")
    reads("smp/src/main/java/eu/nordtal/s2/smp/command/SmpCommand.java")
    reads("smp/src/main/java/eu/nordtal/s2/smp/npc/SpawnNpc.java")
    reads("smp/src/main/java/eu/nordtal/s2/smp/npc/NpcProtection.java")
    reads("smp/src/main/java/eu/nordtal/s2/smp/player/PresenceListener.java")
    reads("smp/src/main/java/eu/nordtal/s2/smp/welcome/SeasonWelcome.java")
}

repositories {
    // jcore and papermc-display-tags are published via JitPack.
    maven("https://jitpack.io")
}

dependencies {
    // The assembled resource pack, for PanelWalk: the chest-row fonts only exist there.
    "resourcePack"(project(":resource-pack", "pack"))

    implementation(project(":paper-common"))
    // Flyway is excluded: this plugin never migrates, and flyway-core drags in Jackson 3.
    implementation(libs.jcore) {
        exclude(group = "org.flywaydb")
    }

    implementation(libs.hikaricp)

    // jcore declares jdbi3-postgres in runtime scope only.
    implementation(libs.jdbi.postgres)

    // Never shaded: a bundled copy of the API would not match the running DisplayTags plugin's classes.
    compileOnly(libs.display.tags)

    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql.driver)
}

messageSpec {
    specClass.set("eu.nordtal.s2.smp.SmpMessages")
}
