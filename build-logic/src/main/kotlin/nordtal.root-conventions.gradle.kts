// CONVENTIONS.md for everything outside a module: build scripts, build-logic, workflows and
// documentation at the root. `conventions.root.comments=true` enforces the tracker-ID rule here.

import eu.nordtal.s2.build.CheckNoTrackerIds
import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("base")
    id("com.diffplug.spotless")
}

val libs = the<LibrariesForLibs>()

repositories {
    mavenCentral()
}

spotless {
    kotlinGradle {
        target("*.gradle.kts", "*/build.gradle.kts", "build-logic/*.gradle.kts", "build-logic/src/**/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
    kotlin {
        target("build-logic/src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
}

val checkNoTrackerIds =
    tasks.register<CheckNoTrackerIds>("checkNoTrackerIds") {
        repositoryRoot.set(layout.projectDirectory)
        pathspecs.set(listOf(".") + subprojects.map { ":(exclude)" + projectDir.toPath().relativize(it.projectDir.toPath()) })
        enforced.set(findProperty("conventions.root.comments")?.toString()?.toBoolean() ?: false)
    }

tasks.named("check") {
    dependsOn(checkNoTrackerIds)
}
