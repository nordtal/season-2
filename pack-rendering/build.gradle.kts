import eu.nordtal.season.build.GlyphPreviewArguments

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

// GlyphPreviews, which renders a glyph with this module's code for Nordtal Designer. No plugin ships it.
val preview =
    sourceSets.create("preview") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
configurations.named(preview.implementationConfigurationName) { extendsFrom(configurations.implementation.get()) }

// sh gradlew :pack-rendering:renderGlyphPreviews -Pfont=nordtal:gui_r{row} -Pcode=FE100, as this module's README describes.
tasks.register<JavaExec>("renderGlyphPreviews") {
    group = "nordtal"
    description = "Renders one glyph in the menu panel, the HUD line and the board as text-component JSON."
    classpath = preview.runtimeClasspath
    mainClass.set("eu.nordtal.season.packrendering.preview.GlyphPreviews")
    val resourcePack = rootProject.layout.projectDirectory.dir("resource-pack")
    val previews = layout.buildDirectory.file("glyph-previews.json")
    argumentProviders.add(
        objects.newInstance<GlyphPreviewArguments>().apply {
            allocation.set(resourcePack.file("glyphs.json"))
            assets.set(resourcePack.dir("src/assets"))
            font.set(providers.gradleProperty("font"))
            code.set(providers.gradleProperty("code"))
            mapOf("rows" to "rows", "row" to "row", "x" to "x", "text" to "text", "board-width" to "boardWidth")
                .forEach { (argument, property) ->
                    options.putAll(providers.gradleProperty(property).map { mapOf(argument to it) }.orElse(emptyMap()))
                }
            out.set(previews)
        },
    )
}

tasks.named<ProcessResources>("processResources") {
    from(glyphResourceFiles)
}

// Files outside this module that the HUD and pack tests read.
repositoryRootTestInputs {
    readsTree("smp/src/main/resources/messages")
    readsTree("smp/src/main/resources/documents")
    readsTree("hunger-games/src/main/resources/documents")
    readsTree("paper-common/src/main/resources/documents")
    readsTree("limbo/src/main/resources/documents")
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

    "previewImplementation"(libs.adventure.api)
    "previewImplementation"(libs.adventure.minimessage)
    "previewImplementation"(libs.adventure.text.serializer.gson)
    "previewImplementation"(libs.gson)

    testImplementation(preview.output)
    testImplementation(libs.adventure.text.serializer.gson)
    testImplementation(testFixtures(project(":common")))
    testImplementation(libs.adventure.api)
    testImplementation(libs.adventure.minimessage)
    testImplementation(libs.gson)
    testImplementation(libs.slf4j.api)
    testRuntimeOnly(libs.logback.classic)
}
