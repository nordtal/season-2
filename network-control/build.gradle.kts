plugins {
    id("nordtal.velocity-plugin")
}

repositories {
    maven("https://repo.simplecloud.app/snapshots")
    maven("https://buf.build/gen/maven")
}

dependencies {
    // Provided at runtime by the simplecloud-api platform plugin. Shading it causes
    // class-loading conflicts, so it must stay compileOnly.
    compileOnly(libs.simplecloud.api)
}
