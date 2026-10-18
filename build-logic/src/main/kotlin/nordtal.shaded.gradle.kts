// Builds the one deployable jar. The task renames the thin jar, so that both jars do not write the same file.

plugins {
    id("nordtal.java-base")
    id("com.gradleup.shadow")
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("thin")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")

    // Duplicates stay for service files only, so that mergeServiceFiles() sees them.
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    // Otherwise the last META-INF/services file wins, and ServiceLoader loses the others.
    mergeServiceFiles()
}

tasks.named("build") {
    dependsOn("shadowJar")
}
