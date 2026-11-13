import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("nordtal.paper-plugin")
    id("nordtal.message-spec")
    id("nordtal.plugin-descriptor")
}

// Steward shows the settings of this module as this descriptor says. steward-agent reads it from the jar.
pluginDescriptor {
    followsMessages.set(true)
    // Steward edits these groups with their own editors and not with cards from the schema.
    editors.put("milestones", "milestones")
    editors.put("advancement-awards", "advancement-awards")
    editors.put("wheel", "wheel")
}

// The base class calls prepare() and enable() before any code reads the fields of SmpPlugin.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        option("NullAway:KnownInitializers", "eu.nordtal.season.smp.SmpPlugin.prepare,eu.nordtal.season.smp.SmpPlugin.enable")
    }
}

// Tests read these files outside the source sets. Without this declaration, an edit leaves :smp:test UP-TO-DATE.
repositoryRootTestInputs {
    reads("compose.yml")
    reads("deploy/minecraft/entrypoint.sh")
    readsTree("smp/src/main/resources/documents/smp")
}

// The default wheel prizes for the pack's wheel, written from DefaultSmp on the plugin's classpath without Paper.
val prizeList =
    sourceSets.create("prizeList") {
        compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
        runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
    }
val writeWheelPrizes =
    tasks.register<JavaExec>("writeWheelPrizes") {
        description = "Writes the default wheel prizes for the resource pack's wheel."
        classpath = prizeList.runtimeClasspath
        mainClass.set("eu.nordtal.season.smp.config.WheelPrizeList")
        val list = layout.buildDirectory.file("generated/wheel-prizes/wheel-prizes.json")
        outputs.file(list).withPropertyName("list")
        argumentProviders.add(CommandLineArgumentProvider { listOf(list.get().asFile.path) })
    }
configurations.consumable("wheelPrizes") {
    outgoing.artifact(layout.buildDirectory.file("generated/wheel-prizes/wheel-prizes.json")) { builtBy(writeWheelPrizes) }
}

// The wheel's text documents with their declarations, which steward-agent reads to draw the spin button's label.
val wheelDocuments =
    tasks.register<Sync>("wheelDocuments") {
        description = "Stages smp's documents and their declarations for steward-agent's pack builds."
        from(tasks.named("messageSchema")) { include("documents/smp/**") }
        from(layout.projectDirectory.dir("src/main/resources")) { include("documents/smp/**") }
        into(layout.buildDirectory.dir("wheel-documents"))
    }
configurations.consumable("wheelDocuments") {
    outgoing.artifact(layout.buildDirectory.dir("wheel-documents")) { builtBy(wheelDocuments) }
}

dependencies {
    // The assembled resource pack. The menu tests measure fonts that exist only in it.
    "resourcePack"(project(":resource-pack", "pack"))

    testImplementation(testFixtures(project(":common")))
    testImplementation(testFixtures(project(":pack-rendering")))

    // :database and :settings only compile against JDBI.
    implementation(libs.jdbi.core)
    implementation(libs.jdbi.sqlobject)

    implementation(libs.hikaricp)

    implementation(libs.jdbi.postgres)

    // The :display-tags module, shaded into this plugin.
    implementation(project(":display-tags"))

    testImplementation(testFixtures(project(":database")))
    testImplementation(testFixtures(project(":settings")))
    testImplementation(libs.postgresql.driver)

    // Paper provides Gson to the plugin, so the prize list brings its own.
    "prizeListImplementation"(libs.gson)
}

messageSpec {
    specClasses.add("eu.nordtal.season.smp.SmpMessages")
    documentClasses.add("eu.nordtal.season.smp.wheel.WheelDialog")
    documentClasses.add("eu.nordtal.season.smp.grave.GraveWindow")
    documentClasses.add("eu.nordtal.season.smp.npc.HandInGui")
    documentClasses.add("eu.nordtal.season.smp.npc.ObjectiveDialog")
    documentClasses.add("eu.nordtal.season.smp.command.SmpAdmin")
    documentClasses.add("eu.nordtal.season.smp.player.PlayerSurfaces")
    documentClasses.add("eu.nordtal.season.smp.SmpTexts")
    documentClasses.add("eu.nordtal.season.smp.milestone.MilestoneNames")
    documentClasses.add("eu.nordtal.season.smp.board.Boards")
    documentClasses.add("eu.nordtal.season.smp.wheel.Wheel")
    documentClasses.add("eu.nordtal.season.smp.takein.TakeInWindow")
    documentClasses.add("eu.nordtal.season.smp.duel.Duels")
    documentClasses.add("eu.nordtal.season.smp.duel.Negotiation")
    documentClasses.add("eu.nordtal.season.smp.duel.NegotiationDialog")
    documentClasses.add("eu.nordtal.season.smp.progress.ObjectiveEngine")
    documentClasses.add("eu.nordtal.season.smp.progress.AdvancementListener")
    documentClasses.add("eu.nordtal.season.smp.grave.GraveListener")
    documentClasses.add("eu.nordtal.season.smp.grave.Graves")
    documentClasses.add("eu.nordtal.season.smp.protect.ProtectionListener")
    documentClasses.add("eu.nordtal.season.smp.travel.PortalGate")
    documentClasses.add("eu.nordtal.season.smp.npc.SpawnNpc")
    documentClasses.add("eu.nordtal.season.smp.rail.StationDialog")
    documentClasses.add("eu.nordtal.season.smp.rail.RideDialog")
    documentClasses.add("eu.nordtal.season.smp.rail.StationMenus")
    documentClasses.add("eu.nordtal.season.smp.rail.NetworkCarts")
    documentClasses.add("eu.nordtal.season.smp.rail.RideFeedback")
    documentClasses.add("eu.nordtal.season.smp.rail.StationRailListener")
}
