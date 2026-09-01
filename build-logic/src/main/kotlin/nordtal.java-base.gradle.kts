// Everything every module shares: Java 25, UTF-8, JUnit, the nordtal group and the
// repo-wide version from the root gradle.properties.

import eu.nordtal.s2.build.CheckSourcesTracked
import eu.nordtal.s2.build.RepositoryRootTestInputs

plugins {
    id("java")
}

group = "eu.nordtal"

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

dependencies {
    "testImplementation"(platform("org.junit:junit-bom:6.0.0"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

// A test that reads a file at the repository root has to say so, or Gradle reports the task
// UP-TO-DATE after that file is edited. See RepositoryRootTestInputs for why two tests do it.
val repositoryRootTestInputs = extensions.create<RepositoryRootTestInputs>(
    "repositoryRootTestInputs", rootProject.layout.projectDirectory)

tasks.named<Test>("test") {
    useJUnitPlatform()
    inputs.files(repositoryRootTestInputs.files)
        .withPropertyName("repositoryRootTestInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// A source file Git ignores compiles here and does not exist on CI. See CheckSourcesTracked; the
// task is on `check` so that every local `./gradlew build` asks the question, which is the only
// place it can still be answered cheaply.
// Both values are read here rather than inside the task block: a Task is ExtensionAware too, so
// `the<SourceSetContainer>()` in there resolves against the task and finds nothing.
val sourceDirectoriesOfEverySourceSet = the<SourceSetContainer>().map { it.allSource.srcDirs }
val repositoryRootDirectory = rootProject.layout.projectDirectory

val checkSourcesTracked = tasks.register<CheckSourcesTracked>("checkSourcesTracked") {
    sourceDirectories.from(sourceDirectoriesOfEverySourceSet)
    repositoryRoot.set(repositoryRootDirectory)
}

tasks.named("check") {
    dependsOn(checkSourcesTracked)
}
