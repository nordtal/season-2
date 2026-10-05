import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("nordtal.paper-plugin")
    id("nordtal.message-spec")
    id("nordtal.plugin-descriptor")
}

// How Steward shows this module's settings; steward-agent reads it out of the jar.
pluginDescriptor {
    displayName.set("SMP")
    // The track is drawn compactly by Steward's own milestones editor rather than as cards from its schema.
    editors.put("milestones", "milestones")
}

// The base runs prepare() and enable() before anything reads SmpPlugin's fields.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        option("NullAway:KnownInitializers", "eu.nordtal.s2.smp.SmpPlugin.prepare,eu.nordtal.s2.smp.SmpPlugin.enable")
    }
}

// Files outside every source set that tests read; undeclared, an edit would leave :smp:test UP-TO-DATE.
repositoryRootTestInputs {
    reads("compose.yml")
    reads("deploy/minecraft/entrypoint.sh")
}

dependencies {
    // The assembled resource pack: the fonts the menu tests measure against only exist there.
    "resourcePack"(project(":resource-pack", "pack"))

    testImplementation(testFixtures(project(":common")))
    testImplementation(testFixtures(project(":pack-rendering")))

    // JDBI, which :database and :settings only compile against.
    implementation(libs.jdbi.core)
    implementation(libs.jdbi.sqlobject)

    implementation(libs.hikaricp)

    implementation(libs.jdbi.postgres)

    // The name tags, shaded into this plugin.
    implementation(project(":display-tags"))

    testImplementation(testFixtures(project(":database")))
    testImplementation(testFixtures(project(":settings")))
    testImplementation(libs.postgresql.driver)
}

messageSpec {
    specClasses.add("eu.nordtal.s2.smp.SmpMessages")
}
