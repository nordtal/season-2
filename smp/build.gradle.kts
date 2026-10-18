import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("nordtal.paper-plugin")
    id("nordtal.message-spec")
    id("nordtal.plugin-descriptor")
}

// Steward shows the settings of this module as this descriptor says. steward-agent reads it from the jar.
pluginDescriptor {
    followsMessages.set(true)
    // Steward edits the milestones group with its milestones editor and not with cards from the schema.
    editors.put("milestones", "milestones")
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
}
