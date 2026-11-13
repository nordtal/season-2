// Replaces the underground of the custom overworld with a generated one, offline on copies of the region files.

plugins {
    id("nordtal.java-base")
    id("application")
}

application {
    mainClass.set("eu.nordtal.season.underground.Underground")
    // One region with its margin is five layers of 157 million cells.
    applicationDefaultJvmArgs = listOf("-Xmx1500m")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
