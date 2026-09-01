plugins {
    id("java")
    id("java-library")
    id("com.gradleup.shadow") version "9.6.1"
    id("maven-publish")
}


group = "eu.nordtal"
version = System.getenv("VERSION") ?: "local"


repositories {
    mavenCentral()
}

java {
    // season-2 runs on Java 25; jcore follows with the 2.0 major.
    // Verified 2026-08-30: every dependency below targets Java 17 bytecode or lower, so none blocks this.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

// All versions below were read from the authoritative maven-metadata.xml on repo1.maven.org on 2026-08-30.
dependencies {
    // -- api: types that appear in jcore's own public signatures, plus the deliberate org-wide toolbox --

    // https://mvnrepository.com/artifact/org.jetbrains/annotations  (@NotNull / @Nullable on jcore signatures)
    api("org.jetbrains:annotations:26.0.2")

    // https://mvnrepository.com/artifact/org.slf4j/slf4j-api  (facade only - the backend is the consumer's choice)
    api("org.slf4j:slf4j-api:2.0.18")

    // https://mvnrepository.com/artifact/org.apache.commons/commons-lang3  (org-wide toolbox, see README)
    api("org.apache.commons:commons-lang3:3.18.0")

    // https://mvnrepository.com/artifact/commons-io/commons-io  (org-wide toolbox, see README)
    api("commons-io:commons-io:2.20.0")

    // https://mvnrepository.com/artifact/com.google.code/gson  (config values are serialized through Gson)
    // ConfigLoader#gsonBuilder() returns a GsonBuilder, so this is part of the public contract.
    // 2.14.0 is both the current release and exactly what Paper 26.2 ships in its libraries/
    // directory, so a Paper plugin can leave it out of its shaded jar - verified 2026-08-30.
    api("com.google.code.gson:gson:2.14.0")

    // https://mvnrepository.com/artifact/org.yaml/snakeyaml  (the YAML reader/writer under CommentedConfiguration)
    // 2.6 for the same reason as Gson above: current release and Paper 26.2's own version.
    api("org.yaml:snakeyaml:2.6")

    // https://mvnrepository.com/artifact/org.jdbi/jdbi3-core  (Jdbi is returned by Database#jdbi())
    api("org.jdbi:jdbi3-core:3.54.0")

    // https://mvnrepository.com/artifact/org.jdbi/jdbi3-sqlobject
    // Consumers must be able to declare @SqlQuery / @SqlUpdate DAO interfaces against jcore's Database.
    api("org.jdbi:jdbi3-sqlobject:3.54.0")

    // -- implementation / runtimeOnly: wiring the consumer needs at runtime but never compiles against --

    // https://mvnrepository.com/artifact/org.jdbi/jdbi3-postgres  (installed inside Database, never exposed)
    implementation("org.jdbi:jdbi3-postgres:3.54.0")

    // https://mvnrepository.com/artifact/com.zaxxer/HikariCP  (Database#dataSource() returns javax.sql.DataSource)
    implementation("com.zaxxer:HikariCP:7.1.0")

    // https://mvnrepository.com/artifact/org.flywaydb/flyway-core  (Database#migrate() returns a plain int)
    implementation("org.flywaydb:flyway-core:13.4.0")

    // https://mvnrepository.com/artifact/org.flywaydb/flyway-database-postgresql
    implementation("org.flywaydb:flyway-database-postgresql:13.4.0")

    // https://mvnrepository.com/artifact/org.postgresql/postgresql  (JDBC driver, loaded by service lookup)
    runtimeOnly("org.postgresql:postgresql:42.7.13")

    // -- test --

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // https://mvnrepository.com/artifact/org.testcontainers/postgresql
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")

    // A logging backend for jcore's own tests only. Deliberately NOT exported - see README.
    // https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.18")
}

tasks.register("sourcesJar", Jar::class) {
    from(sourceSets.main.get().allSource)
    archiveClassifier.set("sources")
}

tasks.register("javadocJar", Jar::class) {
    from(tasks.javadoc)
    archiveClassifier.set("javadoc")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            // maven-publish does not pick these up by convention the way the Sonatype plugin
            // did, so consumers get no in-IDE sources or docs unless they are added explicitly.
            artifact(tasks["sourcesJar"])
            artifact(tasks["javadocJar"])
        }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// A source file Git ignores compiles here and does not exist on a fresh checkout, which makes the
// local build and CI two different programs. nordtal/season-2 lost a release to exactly that on
// 2026-09-02: an unanchored `run/` in .gitignore matched the Java package eu.nordtal.s2.updater.run
// as readily as a server working directory, and an *ignored* file is not an untracked one - so
// `git status` stayed clean the whole time. .gitignore is anchored here for the same reason; this
// asks Git the question anyway, on every `./gradlew build`, before the commit that would hide it.
val repositoryRootDirectory = layout.projectDirectory.asFile
val sourceDirectoriesOfEverySourceSet = sourceSets.flatMap { it.allSource.srcDirs }

val checkSourcesTracked = tasks.register("checkSourcesTracked") {
    group = "verification"
    description = "Fails when a source file is ignored by Git and therefore missing from the repository."

    // .gitignore, the global ignore file and the index are inputs no task can declare, so there is
    // no honest up-to-date check here. The work is one `git` call.
    outputs.upToDateWhen { false }

    val taskPath = path
    doLast {
        if (!repositoryRootDirectory.resolve(".git").exists()) return@doLast

        val pathspecs = sourceDirectoriesOfEverySourceSet
            .filter { it.isDirectory }
            .map { repositoryRootDirectory.toPath().relativize(it.toPath()).joinToString("/") }
            .sorted()
        if (pathspecs.isEmpty()) return@doLast

        // Untracked *and* ignored: a tracked file that happens to match an ignore rule is still in
        // the repository, which is all this cares about.
        val command = listOf(
            "git", "ls-files", "--others", "--ignored", "--exclude-standard", "-z", "--"
        ) + pathspecs
        val process = ProcessBuilder(command)
            .directory(repositoryRootDirectory)
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        if (exit != 0) {
            throw GradleException("`${command.joinToString(" ")}` failed with exit code $exit:\n$output")
        }

        val ignored = output.split('\u0000').filter { it.isNotBlank() }
        if (ignored.isNotEmpty()) {
            throw GradleException(buildString {
                appendLine("Git ignores these files, so they are not in the repository:")
                ignored.forEach { appendLine("    $it") }
                appendLine()
                appendLine("They sit under a source directory covered by $taskPath, so this build sees them and a")
                appendLine("build from a fresh checkout does not. Find the rule with")
                appendLine("    git check-ignore -v <path>")
                appendLine("and anchor it in .gitignore (`/build/`, never a bare `build/`), then `git add` the files.")
            })
        }
    }
}

tasks.named("check") {
    dependsOn(checkSourcesTracked)
}
