package eu.nordtal.s2.steward.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.schema.SchemaNode;
import eu.nordtal.jcore.config.schema.SchemaWriter;
import org.junit.jupiter.api.Test;

/**
 * {@code backup.remote}, the offsite target on the backup page.
 *
 * The credentials are {@code @Secret} so the browser never sees them, and the endpoint is empty by default.
 */
class BackupRemoteSpecTest {

    private static SchemaNode remote() {
        final SchemaNode backup =
                SchemaWriter.build(StewardSpec.class).children().get("backup");
        assertNotNull(backup, "StewardSpec's schema has no 'backup' entry at all");
        final SchemaNode remote = backup.children().get("remote");
        assertNotNull(remote, "BackupSpec's schema has no 'remote' section");
        return remote;
    }

    @Test
    void bothCredentialsOfBackupRemoteAreDeclaredSecret() {
        final SchemaNode accessKey = remote().children().get("access-key");
        assertNotNull(accessKey, "RemoteSpec's schema has no 'access-key' entry");
        assertTrue(accessKey.secret(), "backup.remote.access-key must never be sent to a browser");

        final SchemaNode secretKey = remote().children().get("secret-key");
        assertNotNull(secretKey, "RemoteSpec's schema has no 'secret-key' entry");
        assertTrue(secretKey.secret(), "backup.remote.secret-key must never be sent to a browser");
    }

    @Test
    void theEndpointTheBucketAndThePrefixAreNotSecretATargetIsNotACredential() {
        for (final String key : new String[] {"endpoint", "bucket", "prefix"}) {
            final SchemaNode node = remote().children().get(key);
            assertNotNull(node, "RemoteSpec's schema has no '" + key + "' entry");
            assertFalse(
                    node.secret(),
                    key + " is where the copy goes, not a credential - masking it"
                            + " would hide the one thing an operator needs to read back");
        }
    }

    @Test
    void aFreshFileHasNoOffsiteTargetAndSaysSoByBeingEmpty() {
        final BackupSpec.RemoteSpec remote = new BackupSpec.RemoteSpec() {};
        assertEquals("", remote.endpoint(), "a deployment with no Storage Box must look like one");
        assertEquals("", remote.bucket());
        assertEquals("", remote.prefix());
        assertEquals("", remote.accessKey());
        assertEquals("", remote.secretKey());
    }
}
