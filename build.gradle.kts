import java.util.Base64
import org.gradle.api.credentials.HttpHeaderCredentials
import org.gradle.authentication.http.HttpHeaderAuthentication

plugins {
    id("java")
    id("java-library")
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("maven-publish")
    id("signing")
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

publishing {
    repositories {
        maven {
            name = "Sonatype"
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials(HttpHeaderCredentials::class) {
                val user = (project.findProperty("sonatype.username") as String?) ?: System.getenv("SONATYPE_USERNAME")
                val pass = (project.findProperty("sonatype.password") as String?) ?: System.getenv("SONATYPE_PASSWORD")
                if (user != null && pass != null) {
                    name = "Authorization"
                    value = "Bearer " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
                }
            }
            authentication {
                create<HttpHeaderAuthentication>("header")
            }
        }
        maven {
            name = "SonatypeSnapshots"
            url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            credentials(HttpHeaderCredentials::class) {
                val user = (project.findProperty("sonatype.username") as String?) ?: System.getenv("SONATYPE_USERNAME")
                val pass = (project.findProperty("sonatype.password") as String?) ?: System.getenv("SONATYPE_PASSWORD")
                if (user != null && pass != null) {
                    name = "Authorization"
                    value = "Bearer " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
                }
            }
            authentication {
                create<HttpHeaderAuthentication>("header")
            }
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            artifact(tasks["sourcesJar"])
            artifact(tasks["javadocJar"])

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
    }
}

signing {
    signing {
        val signingKey = project.findProperty("signing.key") as String?
        val signingPassword = project.findProperty("signing.password") as String?

        if (signingKey != null && signingPassword != null) {
            val decodedKey = String(Base64.getDecoder().decode(signingKey))
            useInMemoryPgpKeys(decodedKey, signingPassword)
            sign(publishing.publications["mavenJava"])
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
