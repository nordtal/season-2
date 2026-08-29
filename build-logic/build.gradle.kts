plugins {
    `kotlin-dsl`
}

dependencies {
    // Convention plugins apply these, so build-logic needs them on its own classpath.
    // A plugin id `x.y` is expressed as a dependency by its marker coordinate `x.y:x.y.gradle.plugin`.
    implementation(libs.plugins.shadow.marker())
    implementation(libs.plugins.run.paper.marker())

    // Precompiled script plugins cannot see the `libs` accessor directly. Putting the generated
    // catalog class on the compile classpath is what makes `the<LibrariesForLibs>()` resolve
    // inside them. Gradle has no supported alternative as of 9.7.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}

fun Provider<PluginDependency>.marker(): Provider<String> = map {
    "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version.requiredVersion}"
}
