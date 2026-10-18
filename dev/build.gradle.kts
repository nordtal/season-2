// The local network as a Java program, so that no operating system needs bash. The run configurations in .run/ start Dev.

plugins {
    id("nordtal.java-base")
    id("application")
}

application {
    mainClass.set("eu.nordtal.season.dev.Dev")
}

tasks.named<JavaExec>("run") {
    // `sh gradlew -q :dev:run --args=init` asks its questions on this terminal.
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}

// NordtalQuestionsTest compares the borrowed questions with the table of the installer.
repositoryRootTestInputs {
    reads("deploy/nordtal.sh")
    reads("deploy/dev.env.example")
    readsTree(".run")

    // LocalProjectTest reads the sources of this module for a compose command line of their own.
    readsTree("dev/src/main/java")
}

dependencies {
    // The Compose class of steward-agent writes every docker compose command line. dev runs the same lines on this terminal.
    implementation(project(":steward-agent"))
}
