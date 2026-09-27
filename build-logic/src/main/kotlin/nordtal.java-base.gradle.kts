// Shared by every module: Java 25, UTF-8, JUnit, the group and the repo-wide version.

import eu.nordtal.s2.build.CheckSourcesTracked
import eu.nordtal.s2.build.RepositoryRootTestInputs

plugins {
    id("java")
    id("nordtal.conventions")
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

// A test reading a file at the repository root declares it, or Gradle skips it after an edit.
val repositoryRootTestInputs =
    extensions.create<RepositoryRootTestInputs>("repositoryRootTestInputs", rootProject.layout.projectDirectory)

tasks.named<Test>("test") {
    useJUnitPlatform()
    inputs
        .files(repositoryRootTestInputs.files)
        .withPropertyName("repositoryRootTestInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Read outside the task block, where the<SourceSetContainer>() would resolve against the task.
val sourceDirectoriesOfEverySourceSet = the<SourceSetContainer>().map { it.allSource.srcDirs }
val repositoryRootDirectory = rootProject.layout.projectDirectory

// Catches a source file Git ignores but the local build still compiles; see CheckSourcesTracked.
val checkSourcesTracked =
    tasks.register<CheckSourcesTracked>("checkSourcesTracked") {
        sourceDirectories.from(sourceDirectoriesOfEverySourceSet)
        repositoryRoot.set(repositoryRootDirectory)
    }

tasks.named("check") {
    dependsOn(checkSourcesTracked)
}
