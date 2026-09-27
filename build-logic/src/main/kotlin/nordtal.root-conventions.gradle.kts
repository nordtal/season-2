// Checks CONVENTIONS.md outside the modules; `conventions.root.comments=true` enforces the comment rules.

import eu.nordtal.s2.build.CheckNoDashPunctuation
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

val noDashPunctuation =
    tasks.register<CheckNoDashPunctuation>("noDashPunctuation") {
        repositoryRoot.set(layout.projectDirectory)
        pathspecs.set(listOf(".") + subprojects.map { ":(exclude)" + projectDir.toPath().relativize(it.projectDir.toPath()) })
        enforced.set(findProperty("conventions.root.comments")?.toString()?.toBoolean() ?: false)
    }

tasks.named("check") {
    dependsOn(checkNoTrackerIds, noDashPunctuation)
}
