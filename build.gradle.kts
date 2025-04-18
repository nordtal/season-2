plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("maven-publish")
}

group = "xyz.growaction"
version = "0.3.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // https://mvnrepository.com/artifact/org.jetbrains/annotations
    implementation("org.jetbrains:annotations:26.0.2")

    // https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
    implementation("ch.qos.logback:logback-classic:1.5.18")

    // https://mvnrepository.com/artifact/com.fasterxml.jackson.core/jackson-databind
    api("com.fasterxml.jackson.core:jackson-databind:2.18.3")

    // https://mvnrepository.com/artifact/org.hibernate.orm/hibernate-core
    api("org.hibernate.orm:hibernate-core:7.0.0.Beta5")

    // https://mvnrepository.com/artifact/org.mariadb.jdbc/mariadb-java-client
    api("org.mariadb.jdbc:mariadb-java-client:3.5.3")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    // https://mvnrepository.com/artifact/org.projectlombok/lombok
    compileOnly("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok:1.18.38")
}


publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}

tasks.test {
    useJUnitPlatform()
}