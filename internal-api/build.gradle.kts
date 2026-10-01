// The wire between steward and the services only it may reach: the guarded server, its client and the bank's shapes.

plugins {
    id("nordtal.java-base")
    id("java-library")
}

dependencies {
    // Every internal service answers through Javalin, and its routes are configured on Javalin's own config.
    api(libs.javalin)

    // The client half sends through the kernel's web client; both halves speak the kernel's JSON.
    implementation(project(":common"))
    // :common takes gson compileOnly, since every platform ships it; a JVM application brings its own.
    implementation(libs.gson)

    testRuntimeOnly(libs.logback.classic)
}
