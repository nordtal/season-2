// The local season 2 network as one Java program, so that nothing on Windows, macOS or Linux needs
// bash to run it. The run configurations in .run/ are Application configurations of Dev.

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
}
