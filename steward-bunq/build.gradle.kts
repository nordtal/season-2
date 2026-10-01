// The only process that holds the bank key: a stateless adapter over bunq's API, which only steward reaches.

plugins {
    id("nordtal.jvm-app")
}

application.mainClass.set("eu.nordtal.s2.stewardbunq.StewardBunq")

repositories {
    maven("https://jitpack.io")
}

dependencies {
    // The guarded server every internal service answers through, and the shapes steward reads.
    implementation(project(":internal-api"))
    implementation(project(":common"))
    implementation(libs.gson)

    // BunqRequestBuilder patches the SDK's own class; read it before touching this or the OkHttp version.
    implementation(libs.bunq.sdk)

    // The patched BunqRequestBuilder extends okhttp3.Request.Builder, which the SDK ships at runtime scope only.
    implementation(libs.okhttp)

    runtimeOnly(libs.logback.classic)

    // StartLineTest counts the lines logged at startup.
    testImplementation(libs.logback.classic)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}
