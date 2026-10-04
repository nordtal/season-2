plugins {
    id("nordtal.java-base")
    id("java-library")
    // RepositoryRoot for tests that read a file outside their source set, ManualScheduler for timed work.
    id("java-test-fixtures")
}

repositoryRootTestInputs {
    reads("resource-pack/src/pack.mcmeta")
    reads("smp/src/main/resources/paper-plugin.yml")
    reads("limbo/src/main/resources/paper-plugin.yml")
    reads("hunger-games/src/main/resources/paper-plugin.yml")
    reads("gradle/libs.versions.toml")
    reads("deploy/minecraft/entrypoint.sh")
    reads("compose.yml")
}

dependencies {
    // Every platform ships Gson, so it is never shaded; a JVM application brings its own.
    compileOnly(libs.gson)
    testImplementation(libs.gson)
    testFixturesCompileOnly(libs.jspecify)

    testRuntimeOnly(libs.logback.classic)
}
