plugins {
    id("nordtal.java-base")
    id("java-library")
    // RepositoryRoot, for every module whose tests read a file outside their own source set.
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

    // ReadinessWiringTest reads where each kind of process beats.
    reads("paper-common/src/main/java/eu/nordtal/s2/papercommon/plugin/NordtalPlugin.java")
    reads("proxy/src/main/templates/eu/nordtal/s2/proxy/ProxyPlugin.java")
    reads("discord-bot/src/main/java/eu/nordtal/s2/discordbot/AccessBot.java")
}

dependencies {
    // Every platform ships Gson, so it is never shaded; a JVM application brings its own.
    compileOnly(libs.gson)
    testImplementation(libs.gson)

    testRuntimeOnly(libs.logback.classic)
}
