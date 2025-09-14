import java.util.Base64

plugins {
    id("java")
    id("java-library")
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("signing")
    id("eu.kakde.gradle.sonatype-maven-central-publisher") version "1.0.6"
}


group = "eu.nordtal"
version = "1.0.0"


repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    // https://mvnrepository.com/artifact/org.jetbrains/annotations
    api("org.jetbrains:annotations:26.0.2")

    // https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
    api("ch.qos.logback:logback-classic:1.5.18")

    // https://mvnrepository.com/artifact/org.apache.commons/commons-lang3
    api("org.apache.commons:commons-lang3:3.18.0")

    // https://mvnrepository.com/artifact/commons-io/commons-io
    api("commons-io:commons-io:2.20.0")

    // https://mvnrepository.com/artifact/com.fasterxml.jackson.core/jackson-databind
    api("com.fasterxml.jackson.core:jackson-databind:2.19.2")

    // https://mvnrepository.com/artifact/org.hibernate.orm/hibernate-core
    api("org.hibernate.orm:hibernate-core:7.0.7.Final")

    // https://mvnrepository.com/artifact/org.mariadb.jdbc/mariadb-java-client
    api("org.mariadb.jdbc:mariadb-java-client:3.5.4")

    // https://mvnrepository.com/artifact/dev.morphia.morphia/morphia-core
    api("dev.morphia.morphia:morphia-core:2.5.0")

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

val sonatypeUsername: String? by project
val sonatypePassword: String? by project

sonatypeCentralPublishExtension {
    groupId.set("eu.nordtal")
    artifactId.set("jcore")
    version.set("1.0.0")
    componentType.set("java")
    publishingType.set("USER_MANAGED")

    username.set(System.getenv("SONATYPE_USERNAME") ?: sonatypeUsername)
    password.set(System.getenv("SONATYPE_PASSWORD") ?: sonatypePassword)

    pom {
        name.set("nordtal.eu JCore")
        description.set("Common utilities for nordtal.eu projects")
        url.set("https://github.com/nordtal/jcore")

        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }

        developers {
            developer {
                id.set("nordtal")
                name.set("nordtal")
                email.set("info@nordtal.eu")
            }
        }

        scm {
            connection.set("scm:git:https://github.com/nordtal/jcore.git")
            developerConnection.set("scm:git:ssh://github.com/nordtal/jcore.git")
            url.set("https://github.com/nordtal/jcore")
        }
    }
}

signing {
    val signingKey = project.findProperty("signing.key") as String?
    val signingPassword = project.findProperty("signing.password") as String?

    if (signingKey != null && signingPassword != null) {
        val decodedKey = String(Base64.getDecoder().decode(signingKey))
        useInMemoryPgpKeys(decodedKey, signingPassword)
    }
}

tasks.test {
    useJUnitPlatform()
}
