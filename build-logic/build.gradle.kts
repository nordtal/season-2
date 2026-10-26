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
    // Runs a task in a build of its own, to see what it prints on the second run.
    testImplementation(gradleTestKit())
}

tasks.test {
    useJUnitPlatform()
    // The tests read the real pack beside this build.
    val resourcePack = layout.projectDirectory.dir("../resource-pack")
    inputs.file(resourcePack.file("glyphs.json")).withPropertyName("allocation").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(resourcePack.dir("src")).withPropertyName("packSource").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("nordtal.resourcePack", resourcePack.asFile.absolutePath)
    // The Designer's specs read the item types from this file, which a test holds to the types readItem prints.
    val itemTypes = layout.projectDirectory.file("../designer/spec/item-types.json")
    inputs.file(itemTypes).withPropertyName("itemTypes").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("nordtal.itemTypes", itemTypes.asFile.absolutePath)
    // A publish commits through the repository's commit-msg hook, which the tests of Publish run on their titles.
    val commitMsgHook = layout.projectDirectory.file("../.githooks/commit-msg")
    inputs.file(commitMsgHook).withPropertyName("commitMsgHook").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("nordtal.commitMsgHook", commitMsgHook.asFile.absolutePath)
    // The round trip of every vanilla item definition reads the season's client jar from Fabric Loom's cache, where Loom
    // keeps the one it downloads for :bridge-mod and the pack's tasks read.
    systemProperty("nordtal.paperVersion", libs.versions.paper.get())
    systemProperty("nordtal.loomCache", File(gradle.gradleUserHomeDir, "caches/fabric-loom").absolutePath)
}

fun Provider<PluginDependency>.marker(): Provider<String> =
    map {
        "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version.requiredVersion}"
    }
