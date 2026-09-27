plugins {
    id("nordtal.java-base")
    id("nordtal.message-spec")
    id("java-library")
}

// Other modules' sources that the seam tests read as text, so an edit to one reruns :commands:test.
repositoryRootTestInputs {
    reads("smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java")
    reads("smp/src/main/java/eu/nordtal/s2/smp/command/SmpCommand.java")
    // UpdateIsServedEverywhereTest: /update is Target.LOCAL, so each of these five registers it itself.
    reads("hunger-games/src/main/java/eu/nordtal/s2/hungergames/command/HungerGamesCommand.java")
    reads("limbo/src/main/java/eu/nordtal/s2/limbo/command/LimboCommand.java")
    reads("hunger-games/src/main/java/eu/nordtal/s2/hungergames/HungerGamesPlugin.java")
    reads("limbo/src/main/java/eu/nordtal/s2/limbo/LimboPlugin.java")
    reads("proxy/src/main/templates/eu/nordtal/s2/proxy/ProxyPlugin.java")
    reads("discord-bot/src/main/java/eu/nordtal/s2/discordbot/AccessBot.java")
}

dependencies {
    api(project(":common"))

    // MessageBundlesTest loads the shared bundle, and :common leaves the slf4j backend to its consumers.
    testRuntimeOnly(libs.logback.classic)
}

messageSpec {
    specClass.set("eu.nordtal.s2.commands.CommandMessages")
}
