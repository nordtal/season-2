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

## Publishing to Maven Central
The project is configured to publish signed artifacts to Maven Central via Sonatype.
Sonatype now requires a token-based `Authorization` header. The build script
derives the header value by base64 encoding the token's username and password.
Before running `./gradlew publish` you need to supply the following information
via `gradle.properties` (see `gradle.properties.sample`) or environment variables:

```
sonatypeUsername=your-sonatype-username-or-token
sonatypePassword=your-sonatype-password-or-token-secret
signing.key=base64-encoded-GPG-key
signing.password=gpg-key-passphrase
```

Generate an access token in the [Sonatype Central Portal](https://central.sonatype.com/) and import/export your GPG key if required. Snapshot deployments use the `SONATYPE_USERNAME` and `SONATYPE_PASSWORD` environment variables when properties are absent.

### Generating a GPG signing key

Releases are signed with an OpenPGP (GPG) key. If you have never used one before, follow these steps:

1. **Install GPG** – e.g. `apt install gnupg` or `brew install gpg`. Verify with `gpg --version`.
2. **Create a key**:

   ```bash
   gpg --full-generate-key
   ```

   Choose RSA and a passphrase when prompted.
3. **Find the key ID**:

   ```bash
   gpg --list-secret-keys --keyid-format=long
   ```

4. **Publish the public key** so Maven Central can verify signatures:

   ```bash
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   ```

5. **Export the private key for Gradle** and encode it as a single line:

   ```bash
   gpg --armor --export-secret-key <KEY_ID> | base64
   ```

   Put the resulting string into `signing.key` in `gradle.properties`. Use the passphrase you set in step 2 as `signing.password`.