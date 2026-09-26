// CONVENTIONS.md, as far as a tool can check it, for one Java module. Formatting is always enforced.
// `conventions.comments=true` in the module's gradle.properties enforces the comment and tracker-ID
// rules, `conventions.enforced=true` every rule; -P on the command line shows the findings anywhere.

import eu.nordtal.s2.build.CheckNoTrackerIds
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("java")
    id("checkstyle")
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
}

val libs = the<LibrariesForLibs>()

fun flag(name: String) = findProperty(name)?.toString()?.toBoolean() ?: false

val conventionsEnforced = flag("conventions.enforced")
val commentsEnforced = conventionsEnforced || flag("conventions.comments")

dependencies {
    "compileOnly"(libs.jspecify)
    "errorprone"(libs.errorprone.core)
    "errorprone"(libs.nullaway)
}

spotless {
    java {
        target("src/*/java/**/*.java")
        palantirJavaFormat(
            libs.versions.palantir.java.format
                .get(),
        )
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    maxWarnings = 0
    isIgnoreFailures = !commentsEnforced
    isShowViolations = commentsEnforced
    configProperties = mapOf("apiDocSeverity" to "ignore", "codeSeverity" to if (conventionsEnforced) "error" else "ignore")
}

tasks.withType<Checkstyle>().configureEach {
    // A plain File, so the spec does not capture the script and the configuration cache can store it.
    val generated = layout.buildDirectory.get().asFile
    exclude { it.file.startsWith(generated) }
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        enabled = conventionsEnforced
        disableWarningsInGeneratedCode = true
        excludedPaths = ".*/generated/.*"
        // CONVENTIONS.md allows a doc comment of tags only when the name and tags say everything.
        disable("MissingSummary")
        check("NullAway", if (name == "compileJava") CheckSeverity.ERROR else CheckSeverity.OFF)
        option("NullAway:AnnotatedPackages", "eu.nordtal")
    }
    if (conventionsEnforced) options.compilerArgs.add("-Werror")
}

tasks.withType<Test>().configureEach {
    systemProperty("conventions.enforced", conventionsEnforced)
}

val checkNoTrackerIds =
    tasks.register<CheckNoTrackerIds>("checkNoTrackerIds") {
        repositoryRoot.set(rootProject.layout.projectDirectory)
        pathspecs.set(
            listOf(
                rootProject.projectDir
                    .toPath()
                    .relativize(projectDir.toPath())
                    .toString(),
            ),
        )
        enforced.set(commentsEnforced)
    }

tasks.named("check") {
    dependsOn(checkNoTrackerIds)
}
