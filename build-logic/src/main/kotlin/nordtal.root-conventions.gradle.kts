// Checks CONVENTIONS.md outside the modules; `conventions.root.comments=true` enforces the comment rules.

import eu.nordtal.season.build.CheckMarkdownHistory
import eu.nordtal.season.build.CheckNoDashPunctuation
import eu.nordtal.season.build.CheckNoTrackerIds
import eu.nordtal.season.build.CheckSeasonNames
import eu.nordtal.season.build.ForkSeason
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

val checkMarkdownHistory =
    tasks.register<CheckMarkdownHistory>("checkMarkdownHistory") {
        repositoryRoot.set(layout.projectDirectory)
        pathspecs.set(listOf(".") + subprojects.map { ":(exclude)" + projectDir.toPath().relativize(it.projectDir.toPath()) })
    }

val seasonNumber = providers.gradleProperty("season")

val checkSeasonNames =
    tasks.register<CheckSeasonNames>("checkSeasonNames") {
        repositoryRoot.set(layout.projectDirectory)
        season.set(seasonNumber)
    }

tasks.register<ForkSeason>("forkSeason") {
    repositoryRoot.set(layout.projectDirectory)
    season.set(seasonNumber)
}

tasks.named("check") {
    dependsOn(checkNoTrackerIds, noDashPunctuation, checkMarkdownHistory, checkSeasonNames)
}
