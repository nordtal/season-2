plugins {
    id("nordtal.jvm-app")
}

application.mainClass.set("eu.nordtal.s2.paymentsbot.NordTalPayments")

repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation(libs.jda)
    implementation(libs.bunq.sdk)
    implementation(libs.guava)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}
