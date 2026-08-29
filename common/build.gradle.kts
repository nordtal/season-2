plugins {
    id("nordtal.java-base")
}

dependencies {
    // Adventure comes from paper-api / velocity-api at runtime on both platforms,
    // so it is compile-only here and never shaded.
    compileOnly(libs.adventure.api)
    compileOnly(libs.adventure.minimessage)
    compileOnly(libs.annotations)
}
