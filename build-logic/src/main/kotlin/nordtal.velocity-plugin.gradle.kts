// The Velocity plugin; velocity-api is also the processor that writes velocity-plugin.json.

import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("nordtal.shaded")
}

val libs = the<LibrariesForLibs>()

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    "compileOnly"(libs.velocity.api)
    "annotationProcessor"(libs.velocity.api)
    "implementation"(project(":message-rendering"))
}

// The @Plugin class is a template in src/main/templates, expanded with the version from gradle.properties.
val generateTemplates =
    tasks.register<Copy>("generateTemplates") {
        val props = mapOf("version" to project.version.toString())
        inputs.properties(props)
        from(layout.projectDirectory.dir("src/main/templates"))
        into(layout.buildDirectory.dir("generated/sources/templates/java/main"))
        expand(props)
    }

sourceSets.named("main") {
    java.srcDir(generateTemplates)
}
