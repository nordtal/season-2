import eu.nordtal.s2.build.GenerateGlyphAdvances

plugins {
    id("nordtal.java-base")
    id("java-library")
    // FontFile and PackAdvances, for the smp tests that read the assembled pack's fonts.
    id("java-test-fixtures")
}

// The boss bar pills are sized from this advance table, derived from the assembled pack on every build and never
// committed.
val assembledPack = configurations.resolvable("packForAdvances") { extendsFrom(configurations["resourcePack"]) }

val bossbarAdvances =
    tasks.register<GenerateGlyphAdvances>("generateBossbarAdvances") {
        pack.from(assembledPack)
        font.set("nordtal/font/bossbar.json")
        target.set(layout.buildDirectory.file("generated/advances/nordtal/hud/bossbar-advances.properties"))
    }

sourceSets.main {
    resources.srcDir(files(layout.buildDirectory.dir("generated/advances")).builtBy(bossbarAdvances))
}

// Files outside this module that the HUD and pack tests read.
repositoryRootTestInputs {
    readsTree("smp/src/main/resources/messages")
    readsTree("hunger-games/src/main/resources/messages")
    readsTree("limbo/src/main/resources/messages")
}

dependencies {
    // The assembled pack, which the tests read through the nordtal.pack system property.
    "resourcePack"(project(":resource-pack", "pack"))

    api(project(":message-rendering"))

    // Adventure comes from the platform at runtime and is never shaded.
    compileOnly(libs.adventure.api)
    compileOnly(libs.adventure.minimessage)

    testFixturesImplementation(testFixtures(project(":common")))
    testFixturesImplementation(libs.gson)

    testImplementation(testFixtures(project(":common")))
    testImplementation(libs.adventure.api)
    testImplementation(libs.adventure.minimessage)
    testImplementation(libs.gson)
    testImplementation(libs.slf4j.api)
    testRuntimeOnly(libs.logback.classic)
}
