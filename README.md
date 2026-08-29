# nordtal.eu JCore
.. is a Java library built using Gradle that provides all Java applications of nordtal.eu with libraries that are used across the whole organisation and utility classes.

## List of libraries
The following is a list of all libraries that come with java-core. For details view [build.gradle.kts](build.gradle.kts). All dependencies marked as `api("...")` will be available in projects that import java-core.
- **JetBrains Annotations** (usually for `@NotNull` and `@Nullable` annotations)
- **Logback Classic** (we use it to retrieve a `org.slf4j.Logger` from `org.slf4j.LoggerFactory`)
- **Apache Commons Lang 3** (Java utilities)
- **Commons IO** (IO utilities)
- **FasterXML's Jackson databind** (for working with JSON and mapping objects)
- **Hibernate Core** (as ORM, abstract entity repository for MariaDB is provided within java-core)
- **MariaDB Java Client** (as driver)

## List of utilities included
The following is brief overview of the utility classes provided by java-core.

### JSON config loading with config classes / objects
The [JsonConfigLoader](src/main/java/eu/nordtal/jcore/config/JsonConfigLoader.java) provides methods to load and save JSON config files to and from predefined classes / objects which inherit from [JsonConfig](src/main/java/eu/nordtal/jcore/config/JsonConfig.java). The needed inheritance of JsonConfig is currently redundant, but might be used in the future for new features. The JsonConfigLoader automatically adds and removes new config parameters on load.

## Publishing via JitPack
Releases are built and served by [JitPack](https://jitpack.io) straight from this repository — no Sonatype account, no GPG signing, no manual upload.

### Consuming jcore
Add the JitPack repository and the dependency:

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.nordtal:jcore:<tag>")
}
```

Replace `<tag>` with a released git tag (e.g. `1.0.2`). A commit hash or `master-SNAPSHOT` also works.

### Releasing
1. Push a git tag: `git tag 1.0.2 && git push origin 1.0.2`
2. That is it. JitPack builds the tag the first time somebody requests it; the build config lives in [jitpack.yml](jitpack.yml) (Java 21 toolchain, `./gradlew build publishToMavenLocal`). Build status and logs are at https://jitpack.io/#nordtal/jcore.

The version is taken from the `VERSION` environment variable JitPack sets; local builds fall back to `local`.

### Note on the old Maven Central artifact
`eu.nordtal:jcore:1.0.1` on Maven Central is superseded and will not receive further updates. Use the `com.github.nordtal:jcore` coordinates via JitPack instead.
