plugins {
    id("nordtal.java-base")
    id("java-library")
}

dependencies {
    // The pack's JSON is read through Gson, so a number keeps the text it was written in.
    implementation(libs.gson)
}
