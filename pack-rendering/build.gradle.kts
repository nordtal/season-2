plugins {
    id("nordtal.java-base")
    id("java-library")
    // FontFile and PackAdvances, for the smp tests that read the assembled pack's fonts.
    id("java-test-fixtures")
}

// Glyphs and the advance tables the HUD and the menus are sized from, which :resource-pack writes from glyphs.json on
// every build; none of it is committed.
val glyphJava = configurations.dependencyScope("glyphJava")
val glyphJavaFiles = configurations.resolvable("glyphJavaFiles") { extendsFrom(glyphJava.get()) }
val glyphResources = configurations.dependencyScope("glyphResources")
val glyphResourceFiles = configurations.resolvable("glyphResourceFiles") { extendsFrom(glyphResources.get()) }

// Into this module's own build directory, which the conventions' checks recognise as generated code.
val glyphSources =
    tasks.register<Sync>("glyphSources") {
        from(glyphJavaFiles)
        into(layout.buildDirectory.dir("generated/sources/glyphs/java"))
    }

sourceSets.main {
    java.srcDir(glyphSources)
}

tasks.named<ProcessResources>("processResources") {
    from(glyphResourceFiles)
}

// Files outside this module that the HUD and pack tests read.
repositoryRootTestInputs {
    readsTree("smp/src/main/resources/messages")
    readsTree("hunger-games/src/main/resources/messages")
    readsTree("limbo/src/main/resources/messages")
    readsTree("paper-common/src/main/resources/messages")
}

dependencies {
    "glyphJava"(project(":resource-pack", "glyphJava"))
    "glyphResources"(project(":resource-pack", "glyphResources"))

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
