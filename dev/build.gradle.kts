// The local network as a Java program, so no OS needs bash. .run/ starts Dev.

plugins {
    id("nordtal.java-base")
    id("application")
}

application {
    mainClass.set("eu.nordtal.s2.dev.Dev")
}

tasks.named<JavaExec>("run") {
    // `sh gradlew -q :dev:run --args=init` asks its questions on this terminal.
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}

// NordtalQuestionsTest holds the borrowed questions against the installer's own table.
repositoryRootTestInputs {
    reads("deploy/nordtal.sh")
    reads("deploy/dev.env.example")
    readsTree(".run")

    // LocalProjectTest reads this module's sources for a compose command line of their own.
    readsTree("dev/src/main/java")
}

dependencies {
    // steward-agent's Compose writes every docker compose command line; dev runs the same ones on this terminal.
    implementation(project(":steward-agent"))
}
