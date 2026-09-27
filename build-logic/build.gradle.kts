plugins {
    `kotlin-dsl`
}

dependencies {
    // Convention plugins apply these, so build-logic needs them on its own classpath.
    implementation(libs.plugins.shadow.marker())
    implementation(
        libs.plugins.run.paper
            .marker(),
    )
    implementation(libs.plugins.spotless.marker())
    implementation(libs.plugins.errorprone.marker())

    // Makes `the<LibrariesForLibs>()` resolve inside precompiled script plugins.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}

fun Provider<PluginDependency>.marker(): Provider<String> =
    map {
        "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version.requiredVersion}"
    }
