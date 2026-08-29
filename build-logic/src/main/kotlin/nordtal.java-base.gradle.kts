// Everything every module shares: Java 25, UTF-8, JUnit, the nordtal group and the
// repo-wide version from the root gradle.properties.

plugins {
    id("java")
}

group = "eu.nordtal"

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

dependencies {
    "testImplementation"(platform("org.junit:junit-bom:6.0.0"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
