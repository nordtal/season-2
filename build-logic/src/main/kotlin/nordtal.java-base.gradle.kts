// Shared by every module: Java 25, UTF-8, JUnit, the group and the repo-wide version.

import eu.nordtal.season.build.CheckSourcesTracked
import eu.nordtal.season.build.RepositoryRootTestInputs
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("java")
    id("nordtal.conventions")
}

val libs = the<LibrariesForLibs>()

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
    "testImplementation"(platform(libs.junit.bom))
    "testImplementation"(libs.junit.jupiter)
    "testRuntimeOnly"(libs.junit.platform.launcher)
}

// A test reading a file at the repository root declares it, or Gradle skips it after an edit.
val repositoryRootTestInputs =
    extensions.create<RepositoryRootTestInputs>("repositoryRootTestInputs", rootProject.layout.projectDirectory)

// The assembled pack, for modules that declare `resourcePack(project(":resource-pack", "pack"))`.
// Tests find it through the system property `nordtal.pack`.
val resourcePack = configurations.dependencyScope("resourcePack")
val resourcePackFiles =
    configurations.resolvable("resourcePackFiles") {
        extendsFrom(resourcePack.get())
    }

tasks.named<Test>("test") {
    useJUnitPlatform()
    inputs
        .files(repositoryRootTestInputs.files)
        .withPropertyName("repositoryRootTestInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    val pack = resourcePackFiles.map { it.incoming.files }
    inputs
        .files(pack)
        .withPropertyName("resourcePack")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            pack.get().files.map { "-Dnordtal.pack=${it.absolutePath}" }
        },
    )
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
