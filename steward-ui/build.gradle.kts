import com.github.gradle.node.npm.task.NpmInstallTask
import com.github.gradle.node.npm.task.NpmTask
import com.github.gradle.node.task.NodeTask
import eu.nordtal.s2.build.CheckCommentShape
import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("nordtal.jvm-app")
    alias(libs.plugins.node)
}

application.mainClass.set("eu.nordtal.s2.steward.ui.StewardUi")

// Names start() as NullAway's initializer, since StewardUi's app field is set there.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        option(
            "NullAway:KnownInitializers",
            "eu.nordtal.s2.steward.ui.StewardUi.start",
        )
        // Credentials.Key maps bytea to byte[] through JDBI's reflection, which accepts nothing else.
        disable("ArrayRecordComponent")
    }
}

// Files outside this module that tests read as text, so an edit to one of them reruns :steward-ui:test.
repositoryRootTestInputs {
    readsTree("steward-worker/src", "steward-deployer/src")

    reads("deploy/nordtal.sh")
    reads("deploy/README.md")

    reads("deploy/dev.env.example")
    reads("README.md")
    reads("steward-worker/README.md")
    reads("steward-deployer/README.md")
    reads("compose.yml")

    reads("discord-bot/src/main/resources/messages/access/de.properties")
    reads("discord-bot/src/main/resources/messages/access/en.properties")
    reads("steward-ui/language-rules.json")

    reads("resource-pack/src/pack.png")
    reads("steward-ui/frontend/public/icon.png")
    reads("steward-ui/frontend/public/icon-512.png")
    reads("steward-ui/frontend/public/manifest.webmanifest")
    reads("steward-ui/frontend/index.html")

    readsTree("steward-ui/frontend/src")
}

repositories {
    maven("https://jitpack.io")
}

// The frontend: a private Node under build/nodejs, built by Vite and packed into the jar under web/.

val frontendDirectory = layout.projectDirectory.dir("frontend")
val frontendDistDirectory = layout.buildDirectory.dir("frontend-dist")

node {
    version.set("24.21.0")
    download.set(true)
    nodeProjectDir.set(frontendDirectory)
    workDir.set(layout.buildDirectory.dir("nodejs"))
    npmWorkDir.set(layout.buildDirectory.dir("npm"))
    // `ci`, not `install`: it installs exactly package-lock.json, so this host and CI resolve the same tree.
    npmInstallCommand.set("ci")
}

// The install's declared output is `.package-lock.json`, not the ~40 000 files of node_modules.
tasks.named<NpmInstallTask>("npmInstall") {
    nodeModulesOutputFilter {
        include(".package-lock.json")
    }
}

val viteBuild =
    tasks.register<NpmTask>("viteBuild") {
        group = "build"
        description = "Builds the Steward frontend with Vite into build/frontend-dist."
        dependsOn(tasks.named("npmInstall"))
        npmCommand.set(listOf("run", "build"))

        // node_modules is not an input: npmInstall owns it.
        inputs.dir(frontendDirectory.dir("src")).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.dir(frontendDirectory.dir("public")).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs
            .files(
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

        // Vite is a separate process, so the output directory is handed over, or `-PbuildRoot` would not move it.
        environment.put("VITE_OUT_DIR", frontendDistDirectory.map { it.asFile.absolutePath })
        inputs.property("viteOutDir", frontendDistDirectory.map { it.asFile.absolutePath })
    }

/** The frontend's own tests, on `check`, after viteBuild so a type error comes from the build. */
val viteTest =
    tasks.register<NpmTask>("viteTest") {
        group = "verification"
        description = "Runs the Steward frontend's vitest suite."
        dependsOn(tasks.named("npmInstall"))
        mustRunAfter(viteBuild)
        npmCommand.set(listOf("run", "test"))

        // No declared output, so it runs on every `check`: a cached verdict could report a pass nobody ran.
    }

// The Vite dev server on :5173, proxying /api and /auth to the container; `dev ui` starts it.
tasks.register<NpmTask>("viteDev") {
    group = "application"
    description = "Runs the Steward frontend's Vite dev server."
    dependsOn(tasks.named("npmInstall"))
    npmCommand.set(listOf("run", "dev"))
}

// Formatting of the frontend and of every YAML, Markdown and JSON file in the repository; see .oxfmtrc.json.
val oxfmtCheck =
    tasks.register<NodeTask>("oxfmtCheck") {
        group = "verification"
        description = "Checks the repository's TypeScript, YAML, Markdown and JSON formatting with oxfmt."
        dependsOn(tasks.named("npmInstall"))
        script.set(frontendDirectory.file("node_modules/oxfmt/bin/oxfmt"))
        args.set(listOf("--check", "--threads=2"))
        workingDir.set(rootProject.layout.projectDirectory)
    }

val conventionsEnforced = findProperty("conventions.enforced")?.toString()?.toBoolean() ?: false

val oxlint =
    tasks.register<NodeTask>("oxlint") {
        group = "verification"
        description = "Lints the frontend with oxlint's type-aware rules, warnings as errors."
        dependsOn(tasks.named("npmInstall"))
        script.set(frontendDirectory.file("node_modules/oxlint/bin/oxlint"))
        args.set(listOf("--type-aware", "--deny-warnings", "--threads=2"))
        workingDir.set(frontendDirectory)
        ignoreExitValue.set(!conventionsEnforced)
    }

val checkFrontendComments =
    tasks.register<CheckCommentShape>("checkFrontendComments") {
        sources.from(fileTree(frontendDirectory.dir("src")) { include("**/*.ts", "**/*.tsx", "**/*.css") })
        repositoryRoot.set(rootProject.layout.projectDirectory)
        enforced.set(conventionsEnforced || (findProperty("conventions.comments")?.toString()?.toBoolean() ?: false))
    }

tasks.named("check") {
    dependsOn(viteTest, oxfmtCheck, oxlint, checkFrontendComments)
}

// The glyphs a message can name, for the translation editor's menu, served under /glyphs/.
val glyphManifest =
    tasks.register<eu.nordtal.s2.build.GlyphManifest>("glyphManifest") {
        group = "build"
        description = "Writes the named glyphs of minecraft:default with their textures."
        names.set(rootProject.layout.projectDirectory.file("pack-rendering/src/main/resources/eu/nordtal/s2/packrendering/glyph-names.txt"))
        assets.set(rootProject.layout.projectDirectory.dir("resource-pack/src/assets"))
        target.set(layout.buildDirectory.dir("glyphs"))
    }

// Emptying web/ first makes the jar hold exactly the last Vite build, since Vite hashes its file names.
tasks.named<ProcessResources>("processResources") {
    val webDirectory = destinationDir.resolve("web")
    doFirst { webDirectory.deleteRecursively() }
    from(viteBuild) {
        into("web")
    }
    from(glyphManifest) {
        into("web/glyphs")
    }
}

// `:steward-ui:run` takes its environment from the gitignored deploy/dev.env, if present, and never logs it.
// It shares :8080 with `dev ui`. Read through a provider so the configuration cache sees the file change.
val localEnvironment =
    providers
        .fileContents(
            rootProject.layout.projectDirectory.file("deploy/dev.env"),
        ).asText
        .map { text ->
            text
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
                .associate { line ->
                    line.substringBefore('=') to
                        line.substringAfter('=').removeSurrounding("\"").removeSurrounding("'")
                }
        }.orElse(emptyMap())

tasks.named<JavaExec>("run") {
    environment(localEnvironment.get())
}

dependencies {
    // Javalin's JSON mapper is wired to gson in StewardUi; this repo carries no Jackson outside WebAuthn.
    implementation(libs.javalin)

    // The only Jackson in the repository; `auth/WebAuthn` is the one class that speaks it and hands out Strings.
    implementation(libs.webauthn.server.core)

    // Only for JsonProcessingException, which javac must resolve; the runtime uses the library's own Jackson.
    compileOnly(libs.jackson.core)

    // The configuration forms read the Spec model directly.
    implementation(libs.jcore)

    // Web Push, VAPID and aes128gcm; the version catalog says why this library and not the Bouncy Castle fork.
    implementation(libs.webpush)

    // It declares JDBI, HikariCP and slf4j compileOnly, so the bundle below puts them on the runtime classpath.
    implementation(project(":database"))
    // The one loader, database.yml and checks every process shares.
    implementation(project(":settings"))
    implementation(project(":messages"))
    implementation(libs.bundles.access.persistence)

    runtimeOnly(libs.logback.classic)
    runtimeOnly(libs.postgresql.driver)

    // The real ConfigApi, so the integration tests' stand-in worker goes end to end; test scope only.
    testImplementation(project(":steward-worker"))

    testImplementation(libs.cbor)
    // Read by the shutdown test, which counts what steward-ui logs once a follow has lost its server.
    testImplementation(libs.logback.classic)

    testImplementation(testFixtures(project(":database")))
    testImplementation(libs.postgresql.driver)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}
