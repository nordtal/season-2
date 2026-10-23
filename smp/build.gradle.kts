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
    editors.put("duels", "duels")
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
}

messageSpec {
    specClasses.add("eu.nordtal.season.smp.SmpMessages")
    documentClasses.add("eu.nordtal.season.smp.wheel.WheelGui")
    documentClasses.add("eu.nordtal.season.smp.navigate.NavigateGui")
    documentClasses.add("eu.nordtal.season.smp.grave.GraveWindow")
    documentClasses.add("eu.nordtal.season.smp.npc.HandInGui")
    documentClasses.add("eu.nordtal.season.smp.npc.ObjectiveGui")
    documentClasses.add("eu.nordtal.season.smp.command.SmpAdmin")
    documentClasses.add("eu.nordtal.season.smp.command.NavigateCommand")
    documentClasses.add("eu.nordtal.season.smp.player.PlayerSurfaces")
    documentClasses.add("eu.nordtal.season.smp.SmpTexts")
    documentClasses.add("eu.nordtal.season.smp.milestone.MilestoneNames")
    documentClasses.add("eu.nordtal.season.smp.hud.SmpHud")
    documentClasses.add("eu.nordtal.season.smp.navigate.NavigatePage")
    documentClasses.add("eu.nordtal.season.smp.navigate.NavigateListener")
    documentClasses.add("eu.nordtal.season.smp.board.Boards")
    documentClasses.add("eu.nordtal.season.smp.wheel.Wheel")
    documentClasses.add("eu.nordtal.season.smp.duel.Duels")
    documentClasses.add("eu.nordtal.season.smp.progress.ObjectiveEngine")
    documentClasses.add("eu.nordtal.season.smp.progress.AdvancementListener")
    documentClasses.add("eu.nordtal.season.smp.grave.GraveListener")
    documentClasses.add("eu.nordtal.season.smp.grave.Graves")
    documentClasses.add("eu.nordtal.season.smp.protect.ProtectionListener")
    documentClasses.add("eu.nordtal.season.smp.travel.PortalGate")
    documentClasses.add("eu.nordtal.season.smp.npc.NpcListener")
    documentClasses.add("eu.nordtal.season.smp.npc.SpawnNpc")
}
