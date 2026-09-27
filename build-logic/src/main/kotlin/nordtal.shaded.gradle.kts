// Builds the one deployable jar; the thin jar is renamed so both do not write the same file.

plugins {
    id("nordtal.java-base")
    id("com.gradleup.shadow")
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("thin")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")

    // Duplicates are kept for service files only, so mergeServiceFiles() sees them.
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    // Otherwise the last META-INF/services file wins and ServiceLoader loses the others.
    mergeServiceFiles()
}

tasks.named("build") {
    dependsOn("shadowJar")
}
