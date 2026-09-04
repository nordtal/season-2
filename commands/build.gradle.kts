plugins {
    id("nordtal.java-base")
    id("java-library")
}

dependencies {
    api(project(":common"))

    // Messages logs a missing key through slf4j, and :common declares slf4j compileOnly - every
    // process that consumes it brings its own backend. MessageBundlesTest actually loads the shared
    // bundle, so this module's tests need one too; nothing ships with it.
    testRuntimeOnly(libs.logback.classic)
}
