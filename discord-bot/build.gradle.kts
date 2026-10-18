plugins {
    id("nordtal.jvm-app")
    id("nordtal.message-spec")
    id("nordtal.plugin-descriptor")
}

// How Steward shows this module's settings; steward-agent reads it out of the jar.
pluginDescriptor {
    followsMessages.set(true)
}

application.mainClass.set("eu.nordtal.season.discordbot.AccessBot")

// ConfigsTest reads the access blocks of the real .env.example, and LayoutRendererTest the shared layout vectors.
repositoryRootTestInputs {
    reads(".env.example")
    readsTree("discord-bot/src/main")
    readsTree("messages/src/test/resources")
}

dependencies {
    // Without a logging backend every log line vanishes.
    runtimeOnly(libs.logback.classic)

    runtimeOnly(libs.postgresql.driver)

    // The access API and the message system; :database only compiles against JDBI, HikariCP and slf4j.
    implementation(project(":database"))
    implementation(project(":messages"))
    // The one loader, the database group and checks every process shares.
    implementation(project(":settings"))
    implementation(libs.bundles.access.persistence)
    implementation(libs.gson)

    implementation(libs.jda)

    // No bunq SDK here: the key and the patched BunqRequestBuilder live only in :steward-bunq.

    // SchemaCheck validates the schema; only steward-agent migrates.
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    // A real PostgreSQL, for gen_random_uuid(), the partial unique index and numeric rounding.
    testImplementation(testFixtures(project(":common")))
    testImplementation(testFixtures(project(":database")))
    testImplementation(testFixtures(project(":settings")))
    testRuntimeOnly(libs.postgresql.driver)
}

messageSpec {
    specClasses.add("eu.nordtal.season.discordbot.AccessMessages")
    layoutClasses.add("eu.nordtal.season.discordbot.access.discord.ManagedMessages")
    layoutClasses.add("eu.nordtal.season.discordbot.registration.RegisterMessages")
    layoutClasses.add("eu.nordtal.season.discordbot.onboarding.OnboardingMessage")
    layoutClasses.add("eu.nordtal.season.discordbot.Replies")
    layoutClasses.add("eu.nordtal.season.discordbot.access.discord.PurchaseFlow")
    layoutClasses.add("eu.nordtal.season.discordbot.access.discord.LinkFlow")
    layoutClasses.add("eu.nordtal.season.discordbot.onboarding.OnboardingFlow")
    layoutClasses.add("eu.nordtal.season.discordbot.registration.RegisterFlow")
    layoutClasses.add("eu.nordtal.season.discordbot.AdminLog")
    layoutClasses.add("eu.nordtal.season.discordbot.discord.UpdateFeed")
}
