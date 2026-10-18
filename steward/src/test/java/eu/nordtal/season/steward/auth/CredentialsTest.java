package eu.nordtal.season.steward.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Tests {@code steward_credential} and its transports against PostgreSQL. */
class CredentialsTest {

    @Test
    void aKeysTransportsAreARowEachAndGoWithTheKey() throws SQLException {
        // Connects as the role that Steward uses, so a missing grant fails here.
        final TestDatabase database = TestDatabase.fresh();
        final Credentials credentials = new Credentials(database.dataSourceAs(DatabaseRole.STEWARD));
        final DiscordId account = DiscordId.of("51");
        final ByteArray both = new ByteArray(new byte[] {1});
        final ByteArray none = new ByteArray(new byte[] {2});

        credentials.add(
                account,
                both,
                new ByteArray(new byte[] {0}),
                0,
                "usb and nfc",
                Set.of(AuthenticatorTransport.USB, AuthenticatorTransport.NFC),
                null,
                null);
        credentials.add(account, none, new ByteArray(new byte[] {0}), 0, "none said", Set.of(), null, null);

        assertEquals(2, count(database.dataSource(), "SELECT count(*) FROM steward_credential_transport"));
        assertEquals(
                Optional.of(Set.of(AuthenticatorTransport.USB, AuthenticatorTransport.NFC)),
                credentials.of(account).get(0).getTransports());
        assertEquals(Optional.empty(), credentials.of(account).get(1).getTransports());

        credentials.remove(account, both);
        assertEquals(0, count(database.dataSource(), "SELECT count(*) FROM steward_credential_transport"));
    }

    private static int count(final DataSource source, final String sql) throws SQLException {
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
