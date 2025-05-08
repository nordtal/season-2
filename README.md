# Growaction.xyz java-core
.. is a Java library built using Gradle that provides all Java applications of growaction with libraries that are used across the whole organisation and utility classes.

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
The [JsonConfigLoader](src/main/java/xyz/growaction/javacore/config/JsonConfigLoader.java) provides methods to load and save JSON config files to and from predefined classes / objects which inherit from [JsonConfig](src/main/java/xyz/growaction/javacore/config/JsonConfig.java). The needed inheritance of JsonConfig is currently redundant, but might be used in the future for new features. The JsonConfigLoader automatically adds and removes new config parameters on load.
