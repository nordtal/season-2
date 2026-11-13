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
                libs.versions.bridge.loader.minimum
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

// The asset index of the same version, from the version manifest that Loom reads: the sounds of the client live in it,
// not in the jar. A sound of :resource-pack that overrides or plays a vanilla sound reads it.
val vanillaAssetIndex =
    tasks.register<WriteProperties>("vanillaAssetIndex") {
        val index =
            provider {
                LoomGradleExtension
                    .get(project)
                    .minecraftProvider.versionInfo
                    .assetIndex()
            }
        property("id", index.map { it.id() })
        property("url", index.map { it.url() })
        property("sha1", index.map { it.sha1() })
        destinationFile.set(layout.buildDirectory.file("vanilla-asset-index.properties"))
    }
configurations.consumable("vanillaAssetIndex") {
    outgoing.artifact(vanillaAssetIndex)
}

// The release asset the Nordtal Designer looks for.
base.archivesName = "nordtal-bridge"
