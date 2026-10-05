plugins {
    id("nordtal.java-base")
    id("java-library")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    // PacketEvents.
    maven("https://repo.codemc.io/repository/maven-releases/")
    // PlaceholderAPI.
    maven("https://repo.extendedclip.com/releases/")
    // TAB's API is published there only.
    maven("https://jitpack.io")
}

dependencies {
    // A settings group is a spec.
    api(project(":spec"))
    // Its tasks run through paper-common's scheduler; smp, which hosts it, ships paper-common.
    compileOnly(project(":paper-common"))

    compileOnly(libs.paper.api)
    compileOnly(libs.packetevents)
    compileOnly(libs.placeholderapi)
    compileOnly(libs.tab.api)
}
