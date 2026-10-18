package eu.nordtal.season.proxy;

/** The version from gradle.properties. The {@code @Plugin} annotation of {@link ProxyPlugin} reads it. */
final class ProxyVersion {

    static final String VALUE = "${version}";

    private ProxyVersion() {}
}
