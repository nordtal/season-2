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

    // Shadow defaults to EXCLUDE, which drops duplicate entries before mergeServiceFiles() below
    // ever sees them. Lifted only for service files, not a blanket INCLUDE, which would also
    // write every duplicated class into the jar.
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    // Without this the last jar carrying a given META-INF/services file wins and every earlier
    // one is dropped, which breaks ServiceLoader-based discovery such as Flyway's plugin registry.
    mergeServiceFiles()
}

tasks.named("build") {
    dependsOn("shadowJar")
}
