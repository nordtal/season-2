plugins {
    id("nordtal.jvm-app")
    // FakeDaemon and the stand-in agent, which steward's tests talk to as well.
    `java-test-fixtures`
    id("nordtal.plugin-descriptor")
}

application.mainClass.set("eu.nordtal.season.stewardagent.StewardAgent")

// The topology tests read these files outside the module as text, so an edit reruns :steward-agent:test.
repositoryRootTestInputs {
    reads("compose.yml")
    reads("deploy/dev.env.example")
    readsTree("deploy/caddy")
    reads("deploy/jvm/Dockerfile")
    reads("deploy/jvm/entrypoint.sh")
    reads("deploy/minecraft/Dockerfile")
    reads("deploy/minecraft/entrypoint.sh")
    reads(".dockerignore")
    reads(".github/workflows/release.yml")
    reads("smp/src/main/resources/paper-plugin.yml")
}

dependencies {
    implementation(project(":internal-api"))
    implementation(libs.gson)

    // The process clock.
    implementation(project(":common"))

    // :database takes the driver, JDBI, HikariCP and slf4j as compileOnly, so the bundle adds them.
    implementation(project(":database"))
    implementation(libs.bundles.access.persistence)
    implementation(libs.postgresql.driver)
    implementation(libs.jdbi.postgres)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    implementation(project(":settings"))
    // The agent builds the resource pack in a child JVM from its own classpath.
    implementation(project(":pack-builder"))

    runtimeOnly(libs.logback.classic)

    testImplementation(testFixtures(project(":database")))
    testImplementation(testFixtures(project(":settings")))
    testImplementation(testFixtures(project(":common")))
    testImplementation(libs.logback.classic)

    testFixturesImplementation(project(":internal-api"))
    testFixturesImplementation(project(":common"))
    testFixturesImplementation(testFixtures(project(":common")))
    testFixturesImplementation(libs.gson)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

// What the agent builds the resource pack from: the pack without the wheel, the vanilla facts, smp's default prizes
// and smp's documents. The image never downloads the client jar.
val packBase = configurations.dependencyScope("packBase")
val packBaseFiles = configurations.resolvable("packBaseFiles") { extendsFrom(packBase.get()) }
val vanillaFacts = configurations.dependencyScope("vanillaFacts")
val vanillaFactsFiles = configurations.resolvable("vanillaFactsFiles") { extendsFrom(vanillaFacts.get()) }
val wheelPrizes = configurations.dependencyScope("wheelPrizes")
val wheelPrizeFiles = configurations.resolvable("wheelPrizeFiles") { extendsFrom(wheelPrizes.get()) }
val wheelDocuments = configurations.dependencyScope("wheelDocuments")
val wheelDocumentFiles = configurations.resolvable("wheelDocumentFiles") { extendsFrom(wheelDocuments.get()) }
dependencies {
    "packBase"(project(":resource-pack", "packBase"))
    "vanillaFacts"(project(":resource-pack", "vanillaFacts"))
    "wheelPrizes"(project(":smp", "wheelPrizes"))
    "wheelDocuments"(project(":smp", "wheelDocuments"))
}

// compose.yml is baked into the image beside the jar, and the pack's inputs under pack/.
tasks.named<Sync>("imageContext") {
    from(rootProject.layout.projectDirectory.file("compose.yml"))
    into("pack/base") { from(packBaseFiles) }
    into("pack") {
        from(vanillaFactsFiles) { rename { "vanilla-facts.zip" } }
        from(wheelPrizeFiles) { rename { "wheel-prizes.json" } }
        from(wheelDocumentFiles)
    }
}
