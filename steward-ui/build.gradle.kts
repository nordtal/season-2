plugins {
    id("nordtal.jvm-app")
}

application.mainClass.set("eu.nordtal.s2.steward.ui.StewardUi")

repositories {
    maven("https://jitpack.io")
}

dependencies {
    // The web layer. Javalin's json mapper is wired to gson explicitly in StewardUi, because
    // jackson-databind is optional in its POM and this repo does not carry Jackson at all.
    implementation(libs.javalin)

    // Drawn from the Spec model, not hand-written: the configuration forms of §10a.6 read
    // SpecProperty, SpecClass and environmentOverrides() - Java objects, which is why this module
    // takes jcore directly rather than going through a DTO.
    implementation(libs.jcore)

    // AccessDirectory and the migration SQL. It declares JDBI, HikariCP and slf4j compileOnly, so
    // the access-persistence bundle below is what actually puts them on the runtime classpath.
    implementation(project(":common"))
    implementation(libs.bundles.access.persistence)

    // Every command that also exists in game or in Discord. §10b: "comes to the interface" is a new
    // Surface value plus one line per Declaration, never a second implementation of the logic.
    implementation(project(":commands"))

    runtimeOnly(libs.logback.classic)
    runtimeOnly(libs.postgresql.driver)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}
