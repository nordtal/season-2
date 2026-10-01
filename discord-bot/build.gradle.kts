plugins {
    id("nordtal.jvm-app")
    id("nordtal.message-spec")
}

application.mainClass.set("eu.nordtal.s2.discordbot.AccessBot")

// ConfigsTest reads the access blocks of the real .env.example, so the file is a declared input.
repositoryRootTestInputs {
    reads(".env.example")
}

repositories {
    maven("https://jitpack.io")
}

dependencies {
    // The only jcore consumer here: the commented-YAML config and the JDBI persistence layer.
    implementation(libs.jcore)

    // jcore brings no logging backend, and without one every log line vanishes.
    runtimeOnly(libs.logback.classic)

    // Pinned in this repo's catalog rather than inherited from jcore.
    runtimeOnly(libs.postgresql.driver)

    // The access API and the message system; JDBI, HikariCP and slf4j come from jcore at runtime.
    implementation(project(":database"))
    implementation(project(":messages"))
    // The one loader, database.yml and checks every process shares.
    implementation(project(":settings"))

    implementation(libs.jda)

    // No bunq SDK here: the key and the patched BunqRequestBuilder live only in :steward.

    // Compile-only for SchemaCheck: jcore ships Flyway, and two versions on one classpath break its ServiceLoader.
    compileOnly(libs.flyway.core)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    // A real PostgreSQL, for gen_random_uuid(), the partial unique index and numeric rounding.
    testImplementation(testFixtures(project(":database")))
    testRuntimeOnly(libs.postgresql.driver)
}

messageSpec {
    specClass.set("eu.nordtal.s2.discordbot.AccessMessages")
}
