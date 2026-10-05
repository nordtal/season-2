plugins {
    id("nordtal.java-base")
    id("java-library")
    id("nordtal.message-spec")
}

messageSpec {
    specClasses.add("eu.nordtal.season.messages.ValueMessages")
    specClasses.add("eu.nordtal.season.messages.CheckMessages")
}

// Every message bundle, which the bundle tests read off the file.
repositoryRootTestInputs {
    readsTree("database/src/main/resources/messages")
    readsTree("discord-bot/src/main/resources/messages")
    readsTree("hunger-games/src/main/resources/messages")
    readsTree("limbo/src/main/resources/messages")
    readsTree("paper-common/src/main/resources/messages")
    readsTree("proxy/src/main/resources/messages")
    readsTree("smp/src/main/resources/messages")
    readsTree("steward/src/main/resources/messages")
}

dependencies {
    api(project(":common"))

    // The kernel's JSON codec runs on Gson, which every consumer has at runtime and none shades.
    compileOnly(libs.gson)
    testImplementation(libs.gson)

    // No Adventure here: the bot and Steward load this module, and neither has Adventure at runtime.
    compileOnly(libs.slf4j.api)

    testImplementation(testFixtures(project(":common")))
    testImplementation(libs.slf4j.api)
    testRuntimeOnly(libs.logback.classic)
}
