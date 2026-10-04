// A standalone JVM application, shipped as a fat jar and an image from deploy/jvm/Dockerfile.

plugins {
    id("nordtal.shaded")
    id("application")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    manifest {
        attributes["Main-Class"] =
            project.extensions
                .getByType<JavaApplication>()
                .mainClass
                .get()
        // The release a process was built as; steward-agent tells its release from it.
        attributes["Implementation-Version"] = project.version.toString()
    }
}

// What deploy/jvm/Dockerfile copies, under one name: an earlier version's jar is never a second candidate.
val imageContext =
    tasks.register<Sync>("imageContext") {
        group = "distribution"
        description = "Stages what the image template copies into build/image."
        from(tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").flatMap { it.archiveFile }) {
            rename { "app.jar" }
        }
        into(layout.buildDirectory.dir("image"))
    }

tasks.named("assemble") {
    dependsOn(imageContext)
}

// One logging configuration for every service. A module's own logback.xml would be a duplicate and fail the copy.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.file("deploy/jvm/logback.xml"))
}
