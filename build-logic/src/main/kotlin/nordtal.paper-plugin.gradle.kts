// A Paper plugin: the Paper API, the shared code and a local test server via run-paper.

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
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
    // Every shared module arrives through paper-common's API.
    "implementation"(project(":paper-common"))

    // On the test classpath for the plain-value parts of the API, never to start a server.
    "testImplementation"(libs.paper.api)
}

tasks.named<xyz.jpenilla.runpaper.task.RunServer>("runServer") {
    minecraftVersion("26.2")
}

// Paper provides Gson and SnakeYAML to plugins, so a shaded copy only invites a version clash.
tasks.named<ShadowJar>("shadowJar") {
    dependencies {
        exclude(dependency("com.google.code.gson:gson"))
        exclude(dependency("org.yaml:snakeyaml"))
    }
}

// The base opens its pool by the driver's class name, so the jar a server loads has to carry that class.
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

// paper-plugin.yml carries ${version} so the descriptor never drifts from gradle.properties.
tasks.named<ProcessResources>("processResources") {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}
