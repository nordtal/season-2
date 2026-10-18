plugins {
    id("nordtal.java-base")
    id("java-library")
}

dependencies {
    api(project(":messages"))

    // The bot and Steward both draw Discord's components with it, so it is part of this module's API.
    api(libs.jda)
}
