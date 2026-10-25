plugins {
    `kotlin-dsl`
}

dependencies {
    // The convention plugins apply these, so build-logic needs them on its own classpath.
    implementation(libs.plugins.shadow.marker())
    implementation(
        libs.plugins.run.paper
            .marker(),
    )
    implementation(libs.plugins.spotless.marker())
    implementation(libs.plugins.errorprone.marker())

    // Lets `the<LibrariesForLibs>()` resolve inside precompiled script plugins.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The tests read the real pack beside this build.
    val resourcePack = layout.projectDirectory.dir("../resource-pack")
    inputs.file(resourcePack.file("glyphs.json")).withPropertyName("allocation").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(resourcePack.dir("src")).withPropertyName("packSource").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("nordtal.resourcePack", resourcePack.asFile.absolutePath)
}

fun Provider<PluginDependency>.marker(): Provider<String> =
    map {
        "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version.requiredVersion}"
    }
