import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("nordtal.paper-plugin")
    id("nordtal.message-spec")
    id("nordtal.plugin-descriptor")
}

// steward-agent reads the settings display from the jar.
pluginDescriptor {
    followsMessages.set(true)
}

// The plugin base class calls prepare() and enable() before any other code reads the fields.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        option(
            "NullAway:KnownInitializers",
            "eu.nordtal.season.limbo.LimboPlugin.prepare,eu.nordtal.season.limbo.LimboPlugin.enable",
        )
    }
}

dependencies {
    implementation(project(":limbo-protocol"))
    implementation(libs.jdbi.core)
    implementation(libs.jdbi.sqlobject)

    implementation(libs.hikaricp)

    implementation(libs.jdbi.postgres)

    testImplementation(testFixtures(project(":settings")))
}

messageSpec {
    specClasses.add("eu.nordtal.season.limbo.LimboMessages")
}
