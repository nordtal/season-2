plugins {
    id("nordtal.java-base")
    id("java-library")
}

// Every message bundle and every module source the bundle and wiring tests read as text.
repositoryRootTestInputs {
    readsTree("commands/src/main")
    readsTree("discord-bot/src/main/resources/messages")
    readsTree("hunger-games/src/main")
    readsTree("limbo/src/main")
    readsTree("paper-common/src/main")
    readsTree("proxy/src/main")
    readsTree("smp/src/main")
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
