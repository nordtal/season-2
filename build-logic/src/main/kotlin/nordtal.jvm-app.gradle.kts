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
