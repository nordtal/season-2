// A standalone JVM application (the Discord bot), shipped as a runnable fat jar
// and as a container image built from the module's own Dockerfile.

plugins {
    id("nordtal.shaded")
    id("application")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    manifest {
        attributes["Main-Class"] = project.extensions.getByType<JavaApplication>().mainClass.get()
    }
}

// The jar this module's Dockerfile copies must be the only one it could copy - see CheckOneImageJar.
val checkOneImageJar = tasks.register<eu.nordtal.s2.build.CheckOneImageJar>("checkOneImageJar") {
    libraries.set(layout.buildDirectory.dir("libs"))
    artifact.set(project.name)
    // After the jar exists, or a stale one would be the only file there and would pass alone.
    dependsOn(tasks.named("shadowJar"))
}

tasks.named("check") {
    dependsOn(checkOneImageJar)
}
