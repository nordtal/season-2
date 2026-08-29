plugins {
    id("nordtal.jvm-app")
}

application.mainClass.set("eu.nordtal.s2.paymentsbot.NordTalPayments")

repositories {
    maven("https://jitpack.io")
}

dependencies {
    // The only jcore dependency in this repo. It carries the config loader and the MariaDB
    // repository the bot is built on, and pulls in Hibernate, jakarta.persistence,
    // commons-lang3, logback and org.jetbrains:annotations as transitive api dependencies.
    implementation(libs.jcore)

    implementation(libs.jda)
    implementation(libs.bunq.sdk)
    implementation(libs.guava)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}
