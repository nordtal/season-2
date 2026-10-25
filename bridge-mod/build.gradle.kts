import eu.nordtal.season.build.MinecraftVersion

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

// The release asset the Nordtal Designer looks for.
base.archivesName = "nordtal-bridge"
