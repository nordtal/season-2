plugins {
    `kotlin-dsl`
}

dependencies {
    // Convention plugins apply these, so build-logic needs them on its own classpath.
    // A plugin id `x.y` is expressed as a dependency by its marker coordinate `x.y:x.y.gradle.plugin`.
    implementation(libs.plugins.shadow.marker())
    implementation(libs.plugins.run.paper.marker())
}

fun Provider<PluginDependency>.marker(): Provider<String> = map {
    "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version.requiredVersion}"
}
