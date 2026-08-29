// Produces a single runnable/loadable jar. Applied by every deployable module.
// shadowJar takes the plain artifact name, so the thin jar has to move out of the way —
// otherwise both tasks write to build/libs/<module>-<version>.jar.

plugins {
    id("nordtal.java-base")
    id("com.gradleup.shadow")
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("thin")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")
}

tasks.named("build") {
    dependsOn("shadowJar")
}
