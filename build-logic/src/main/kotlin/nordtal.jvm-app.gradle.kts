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
    }
}

// The jar the image template copies must be the only one it could copy; see CheckOneImageJar.
val checkOneImageJar =
    tasks.register<eu.nordtal.s2.build.CheckOneImageJar>("checkOneImageJar") {
        libraries.set(layout.buildDirectory.dir("libs"))
        artifact.set(project.name)
        // After the jar exists, or a stale one would be the only file there and would pass alone.
        dependsOn(tasks.named("shadowJar"))
    }

tasks.named("check") {
    dependsOn(checkOneImageJar)
}

// One logging configuration for every service. A module's own logback.xml would be a duplicate and fail the copy.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.file("deploy/jvm/logback.xml"))
}
