import com.github.gradle.node.npm.task.NpmInstallTask
import com.github.gradle.node.npm.task.NpmTask

plugins {
    id("nordtal.jvm-app")
    alias(libs.plugins.node)
}

application.mainClass.set("eu.nordtal.s2.steward.ui.StewardUi")

repositories {
    maven("https://jitpack.io")
}

// ---------------------------------------------------------------------------------------------
// The frontend.
//
// This host has no Node and is never getting one: the plugin downloads a private copy under
// build/nodejs and every npm call below runs against that. `sh gradlew :steward-ui:build` is the
// only supported way to build the interface; there is no "install node first" step.
//
// The built assets are NOT checked in. Vite writes to build/frontend-dist and processResources
// copies that in under `web/`, so they reach build/resources/main/web and from there the jar -
// which is what StewardUi serves. src/main/resources/web/ would put a build output in Git, and a
// generated file in a repository is a file that is wrong the moment somebody forgets to rebuild.
// ---------------------------------------------------------------------------------------------

val frontendDirectory = layout.projectDirectory.dir("frontend")
val frontendDistDirectory = layout.buildDirectory.dir("frontend-dist")

node {
    // Node 24.x is the active LTS line; 24.21.0 is its newest release (nodejs.org/dist, 2026-09-13).
    version.set("24.21.0")
    download.set(true)
    nodeProjectDir.set(frontendDirectory)
    workDir.set(layout.buildDirectory.dir("nodejs"))
    npmWorkDir.set(layout.buildDirectory.dir("npm"))
    // `ci` rather than `install`: it installs exactly package-lock.json and fails if the lock and
    // package.json disagree, so a build here and a build in CI resolve the same tree.
    npmInstallCommand.set("ci")
}

// The declared output of the install is npm's own record of it, not the whole tree.
//
// `npm ci` deletes and rewrites node_modules every time it runs, so snapshotting all 178 packages
// means fingerprinting ~40 000 files on every build to answer a question one file already answers:
// `.package-lock.json` is written by the install and names every resolved package with its
// integrity hash, so if it matches, node_modules is the tree package-lock.json asked for.
//
// This is also the tidier end of a real failure: `npm ci` run twice in quick succession in the same
// directory raced its own removal here on 2026-09-13 and died with
// `ENOTEMPTY: rmdir node_modules/es-toolkit/compat`. Fewer reruns, fewer chances to hit that.
tasks.named<NpmInstallTask>("npmInstall") {
    nodeModulesOutputFilter {
        include(".package-lock.json")
    }
}

val viteBuild = tasks.register<NpmTask>("viteBuild") {
    group = "build"
    description = "Builds the Steward frontend with Vite into build/frontend-dist."
    dependsOn(tasks.named("npmInstall"))
    npmCommand.set(listOf("run", "build"))

    // Declared inputs, so an untouched frontend does not rebuild. node_modules is deliberately not
    // one of them: npmInstall owns it, and listing 40 000 files here costs more than the build.
    inputs.dir(frontendDirectory.dir("src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(frontendDirectory.dir("public")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        frontendDirectory.file("package.json"),
        frontendDirectory.file("package-lock.json"),
        frontendDirectory.file("index.html"),
        frontendDirectory.file("vite.config.ts"),
        frontendDirectory.file("tsconfig.json"),
        frontendDirectory.file("tsconfig.app.json"),
        frontendDirectory.file("tsconfig.node.json"),
        frontendDirectory.file("components.json"),
    ).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(frontendDistDirectory)
    outputs.cacheIf { true }
}

/**
 * The frontend's own tests.
 *
 * Hung on `check` rather than on `build`, because this is the same promise :steward-ui's Java half
 * makes: a `sh gradlew check` that passes is a claim about the whole module, and a module whose
 * interesting logic - the Ampel's decision, the log window's buffer - is only ever verified by
 * `tsc --noEmit` is a module where "it compiles" was quietly allowed to stand in for "it works".
 * It runs after viteBuild so a type error is reported by the build that exists to report one.
 */
val viteTest = tasks.register<NpmTask>("viteTest") {
    group = "verification"
    description = "Runs the Steward frontend's vitest suite."
    dependsOn(tasks.named("npmInstall"))
    mustRunAfter(viteBuild)
    npmCommand.set(listOf("run", "test"))

    // NO DECLARED OUTPUT, so it runs on every `check`. Vitest produces nothing this build
    // consumes - the outcome is a verdict, not a file - and the obvious way to cache a verdict is a
    // marker file written from a `doLast`, which the configuration cache refuses: a closure in a
    // Kotlin build script holds a reference to the script object. The whole suite is about four
    // seconds, which is a smaller price than a cache that can report a pass nobody ran.
}

tasks.named("check") {
    dependsOn(viteTest)
}

tasks.named<ProcessResources>("processResources") {
    from(viteBuild) {
        into("web")
    }
}

dependencies {
    // The web layer. Javalin's json mapper is wired to gson explicitly in StewardUi, because
    // jackson-databind is optional in its POM and this repo does not carry Jackson at all.
    implementation(libs.javalin)

    // Drawn from the Spec model, not hand-written: the configuration forms of §10a.6 read
    // SpecProperty, SpecClass and environmentOverrides() - Java objects, which is why this module
    // takes jcore directly rather than going through a DTO.
    implementation(libs.jcore)

    // AccessDirectory and the migration SQL. It declares JDBI, HikariCP and slf4j compileOnly, so
    // the access-persistence bundle below is what actually puts them on the runtime classpath.
    implementation(project(":common"))
    implementation(libs.bundles.access.persistence)

    // Every command that also exists in game or in Discord. §10b: "comes to the interface" is a new
    // Surface value plus one line per Declaration, never a second implementation of the logic.
    implementation(project(":commands"))

    runtimeOnly(libs.logback.classic)
    runtimeOnly(libs.postgresql.driver)

    // The endpoints that read rows are tested against a real PostgreSQL with the real migrations
    // applied, for the same reason :common's are: a fake of a database proves the fake works.
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.postgresql.driver)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}
