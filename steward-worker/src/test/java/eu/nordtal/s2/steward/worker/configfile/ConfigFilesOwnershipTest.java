package eu.nordtal.s2.steward.worker.configfile;

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
 * The worker runs as root and edits other services' config volumes through {@code /configs/<service>}.
 *
 * That is a file it does not itself own. A write has to leave that file owned by whoever it belonged to, not by the
 * writing process, because the temp-file-plus-rename {@link ConfigFiles#write} otherwise uses to stay atomic quietly
 * drops exactly that attribute: {@link Files#createTempFile} makes the new file owned by the process running it.
 *
 * Why an in-memory filesystem, and not the real one: the only way to prove ownership survives is to start from a
 * file owned by somebody other than the test process, and the real filesystem does not let an unprivileged process
 * hand that out - while a session running as uid 0 can chown to anything and would make the interesting failure
 * untestable for the opposite reason. Jimfs enforces no privilege model at all: a fabricated owner is carried, or is
 * not, on the write code's own merits, on every host this runs on.
 */
class ConfigFilesOwnershipTest {

    private FileSystem fs;
    private Path file;
    private UserPrincipal owner;
    private GroupPrincipal group;

    @BeforeEach
    void aFileOwnedBySomebodyElse() throws IOException {
        // unix() alone does not turn "posix" on for Files.getFileAttributeView - it has to be named explicitly.
        fs = Jimfs.newFileSystem(Configuration.unix().toBuilder()
                .setAttributeViews("basic", "owner", "posix", "unix")
                .build());
        final Path directory = fs.getPath("/configs/steward-ui");
        Files.createDirectories(directory);
        file = directory.resolve("steward-ui.yml");
        Files.writeString(file, "port: 8080\n");

        // Not the identity Jimfs hands a fresh file - the write below must keep this, not the JVM's own.
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
        // And the edit itself still happened - this is not a test that passed by refusing to write anything.
        assertEquals("port: 9090\n", Files.readString(file));
    }
}
