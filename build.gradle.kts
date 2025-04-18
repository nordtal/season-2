plugins {
    id("java")
    id("java-library")
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("maven-publish")
}

group = "xyz.growaction"
version = "0.3.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // https://mvnrepository.com/artifact/org.jetbrains/annotations
    api("org.jetbrains:annotations:26.0.2")

    // https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
    api("ch.qos.logback:logback-classic:1.5.18")

    // https://mvnrepository.com/artifact/org.apache.commons/commons-lang3
    api("org.apache.commons:commons-lang3:3.17.0")

    // https://mvnrepository.com/artifact/commons-io/commons-io
    api("commons-io:commons-io:2.19.0")

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

tasks.register("sourcesJar", Jar::class) {
    from(sourceSets.main.get().allSource)
    archiveClassifier.set("sources")
}

tasks.register("javadocJar", Jar::class) {
    from(tasks.javadoc)
    archiveClassifier.set("javadoc")
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/growaction/java-core")
            credentials {
                username = project.findProperty("gpr.user").toString()
                password = project.findProperty("gpr.token").toString()
            }
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            artifact(tasks["sourcesJar"])
            artifact(tasks["javadocJar"])
        }
    }
}

tasks.test {
    useJUnitPlatform()
}