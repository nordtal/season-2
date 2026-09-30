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

    reads("smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java")
    reads("limbo/src/main/java/eu/nordtal/s2/limbo/LimboPlugin.java")
    reads("hunger-games/src/main/java/eu/nordtal/s2/hungergames/HungerGamesPlugin.java")

    reads("discord-bot/src/main/java/eu/nordtal/s2/discordbot/AccessBot.java")

    reads("deploy/minecraft/entrypoint.sh")

    reads("gradle/libs.versions.toml")
    reads("compose.yml")

    readsTree("smp/src/main")
    readsTree("limbo/src/main")
    readsTree("hunger-games/src/main")
    readsTree("proxy/src/main")

    // The config packages ConfigSpecExplanationTest walks that no tree above covers.
    readsTree("discord-bot/src/main/java/eu/nordtal/s2/discordbot/config")
    readsTree("steward-ui/src/main/java/eu/nordtal/s2/steward/ui/config")
    readsTree("steward-worker/src/main/java/eu/nordtal/s2/steward/worker/config")
}

dependencies {
    // Every platform ships Gson, so it is never shaded; a JVM application brings its own.
    compileOnly(libs.gson)
    testImplementation(libs.gson)

    testRuntimeOnly(libs.logback.classic)
}
