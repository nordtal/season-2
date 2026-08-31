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

    // Shadow 9 defaults to EXCLUDE, which drops duplicate entries *before* a transformer ever
    // sees them - so mergeServiceFiles() below silently does nothing until this is lifted. It is
    // lifted only for the service files: a blanket INCLUDE would also write every duplicated
    // *class* into the jar, which is 700 KB of dead weight and a pile of Gradle warnings.
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    // Without this the last jar carrying a given META-INF/services file wins and every earlier
    // one is dropped. Not cosmetic: Flyway discovers its entire plugin registry through
    // ServiceLoader, so the shaded bot jar kept 3 of 41 entries and died inside `new Flyway(...)`
    // on a null DryRunConfigurationExtensionStub - the bot could not run its own migrations from
    // its own artifact. Found 2026-08-31 by running the built image; no test catches it, because
    // tests run against the ordinary runtime classpath and never against a shaded jar.
    mergeServiceFiles()
}

tasks.named("build") {
    dependsOn("shadowJar")
}
