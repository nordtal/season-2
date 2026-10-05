package eu.nordtal.season.settings;

/** Opens a service's pool on a test database, as the service opens it from its {@code database} group. */
public final class TestPools {

    private TestPools() {}

    /** Returns a pool the caller closes. */
    public static Database open(final String jdbcUrl, final String username, final String password) {
        return Database.open(
                new DatabaseSpec() {
                    @Override
                    public String jdbcUrl() {
                        return jdbcUrl;
                    }

                    @Override
                    public String username() {
                        return username;
                    }

                    @Override
                    public String password() {
                        return password;
                    }
                },
                "test");
    }
}
