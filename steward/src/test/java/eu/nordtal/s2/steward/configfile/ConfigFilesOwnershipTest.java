package eu.nordtal.s2.steward.configfile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A write through {@code /configs/<service>} keeps the file's owner, although steward runs as root.
 *
 * Jimfs carries a fabricated owner on any host; the real filesystem would need privilege to set one up.
 */
class ConfigFilesOwnershipTest {

    private FileSystem fs;
    private Path file;
    private UserPrincipal owner;
    private GroupPrincipal group;

    @BeforeEach
    void aFileOwnedBySomebodyElse() throws IOException {
        // unix() alone does not enable posix for Files.getFileAttributeView; it has to be named.
        fs = Jimfs.newFileSystem(Configuration.unix().toBuilder()
                .setAttributeViews("basic", "owner", "posix", "unix")
                .build());
        final Path directory = fs.getPath("/configs/steward");
        Files.createDirectories(directory);
        file = directory.resolve("web.yml");
        Files.writeString(file, "port: 8080\n");

        // Not the identity Jimfs hands a fresh file; the write below must keep this one.
        owner = fs.getUserPrincipalLookupService().lookupPrincipalByName("10001");
        group = fs.getUserPrincipalLookupService().lookupPrincipalByGroupName("10001");
        final PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        view.setOwner(owner);
        view.setGroup(group);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }

    @AfterEach
    void closeTheFilesystem() throws IOException {
        fs.close();
    }

    @Test
    void aSaveLeavesTheFileOwnedByWhoeverItBelongedToBefore() throws IOException {
        ConfigFiles.write(file, Map.of("port", ConfigChange.of("9090")));

        final PosixFileAttributes after = Files.readAttributes(file, PosixFileAttributes.class);
        assertEquals(owner.getName(), after.owner().getName());
        assertEquals(group.getName(), after.group().getName());
        assertEquals(PosixFilePermissions.fromString("rw-------"), after.permissions());
        // And the edit itself happened, so the test did not pass by refusing to write.
        assertEquals("port: 9090\n", Files.readString(file));
    }
}
