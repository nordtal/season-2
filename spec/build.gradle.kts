plugins {
    id("nordtal.java-base")
    id("java-library")
}

dependencies {
    // Spec values are read and written through Gson, which every platform ships and no plugin shades.
    compileOnly(libs.gson)
    testImplementation(libs.gson)
}
