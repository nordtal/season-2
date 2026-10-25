// A Paper plugin with the Paper API, the shared code and a local test server through run-paper.

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import eu.nordtal.season.build.MinecraftVersion
import org.gradle.accessors.dm.LibrariesForLibs
import java.util.zip.ZipFile

plugins {
    id("nordtal.shaded")
    id("xyz.jpenilla.run-paper")
}

val libs = the<LibrariesForLibs>()

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    "compileOnly"(libs.paper.api)
    // Every shared module comes through the API of paper-common.
    "implementation"(project(":paper-common"))

    // On the test classpath for the plain-value parts of the API. Tests never start a server.
    "testImplementation"(libs.paper.api)
}

tasks.named<xyz.jpenilla.runpaper.task.RunServer>("runServer") {
    minecraftVersion(MinecraftVersion.of(libs.versions.paper.get()))
}

// Paper provides Gson and SnakeYAML to plugins, so a shaded copy risks a version clash.
tasks.named<ShadowJar>("shadowJar") {
    dependencies {
        exclude(dependency("com.google.code.gson:gson"))
        exclude(dependency("org.yaml:snakeyaml"))
    }
}

// The base opens its pool by the class name of the driver, so the jar that a server loads must contain that class.
val checkShadedDriver =
    tasks.register("checkShadedDriver") {
        val jar = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile }
        inputs.file(jar)
        doLast {
            val file = jar.get().asFile
            ZipFile(file).use { zip ->
                check(zip.getEntry("org/postgresql/Driver.class") != null) {
                    "${file.name} carries no org.postgresql.Driver, so the plugin cannot open its pool"
                }
            }
        }
    }

tasks.named("check") {
    dependsOn(checkShadedDriver)
}

// paper-plugin.yml contains ${version}, so the descriptor always matches gradle.properties.
tasks.named<ProcessResources>("processResources") {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}
