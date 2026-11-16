plugins {
    id("nordtal.java-base")
    id("java-library")
}

// The letter advances that Letters reads, written from glyphs.json by :resource-pack.
val glyphResources = configurations.dependencyScope("glyphResources")
val glyphResourceFiles = configurations.resolvable("glyphResourceFiles") { extendsFrom(glyphResources.get()) }
tasks.named<ProcessResources>("processResources") { from(glyphResourceFiles) }

dependencies {
    "glyphResources"(project(":resource-pack", "glyphResources"))
    api(project(":messages"))

    // Adventure comes from the platform at runtime and is never shaded.
    compileOnly(libs.adventure.api)
    compileOnly(libs.adventure.minimessage)

    testImplementation(libs.adventure.api)
    testImplementation(libs.adventure.minimessage)
    testImplementation(libs.gson)
    testImplementation(libs.slf4j.api)
    testRuntimeOnly(libs.logback.classic)
}
