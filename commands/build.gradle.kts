plugins {
    id("nordtal.java-base")
    id("java-library")
}

// SharedBundleLoadedTest and MessageBundlesTest read other modules' sources as text: what they check
// is a seam between two modules, which neither module's own tests can see. They are in no source set
// of this module, so without these declarations Gradle cannot see them and an edit would leave
// :commands:test UP-TO-DATE - the one check that would have caught the drift being the one that does
// not run.
repositoryRootTestInputs {
    reads("smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java")
    reads("smp/src/main/java/eu/nordtal/s2/smp/command/SmpCommand.java")
    reads("hunger-games/src/main/java/eu/nordtal/s2/hungergames/HungerGamesPlugin.java")
    reads("limbo/src/main/java/eu/nordtal/s2/limbo/LimboPlugin.java")
    reads("network-control/src/main/templates/eu/nordtal/s2/networkcontrol/NetworkControlPlugin.java")
    reads("discord-bot/src/main/java/eu/nordtal/s2/discordbot/AccessBot.java")
}

dependencies {
    api(project(":common"))

    // Messages logs a missing key through slf4j, and :common declares slf4j compileOnly - every
    // process that consumes it brings its own backend. MessageBundlesTest actually loads the shared
    // bundle, so this module's tests need one too; nothing ships with it.
    testRuntimeOnly(libs.logback.classic)
}
