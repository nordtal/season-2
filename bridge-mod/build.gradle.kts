import eu.nordtal.season.build.MinecraftVersion
import net.fabricmc.loom.LoomGradleExtension

plugins {
    alias(libs.plugins.fabric.loom)
    id("nordtal.java-base")
}

val minecraftVersion = MinecraftVersion.of(libs.versions.paper.get())

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation(libs.fabric.loader)
}

tasks.processResources {
    val values =
        mapOf(
            "version" to project.version.toString(),
            "loader" to
                libs.versions.fabric.loader
                    .get(),
            "minecraft" to minecraftVersion,
        )
    inputs.properties(values)
    filesMatching("fabric.mod.json") {
        expand(values)
    }
}

// The client jar that Loom downloads once and checks against the SHA-1 of Mojang's version manifest. A vanilla
// override of :resource-pack starts as a copy from it, so the jar is downloaded and cached once.
configurations.consumable("vanillaClient") {
    outgoing.artifact(provider { LoomGradleExtension.get(project).minecraftProvider.minecraftClientJar })
}

// The release asset the Nordtal Designer looks for.
base.archivesName = "nordtal-bridge"
