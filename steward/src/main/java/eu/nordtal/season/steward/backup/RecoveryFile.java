package eu.nordtal.season.steward.backup;

/**
 * Writes the recovery file of one repository: its password and the steps that restore from it without steward.
 * The steps use rclone's own OAuth client, which needs read access to all of Drive to see the files steward wrote.
 */
public final class RecoveryFile {

    private RecoveryFile() {}

    public static String name(final String series) {
        return "nordtal-" + series + "-backup-recovery.txt";
    }

    public static String text(final Destinations.Recovery recovery) {
        return """
                Nordtal backup recovery file

                This file holds the only copy of the repository password outside Steward. Keep it offline.
                Anyone with this file and access to the Google account can read the backups.

                Series:            %1$s
                Google account:    %2$s
                Drive folder:      %3$s
                Drive folder ID:   %4$s
                Repository folder: %5$s
                Restic password:   %6$s

                Restore without Steward

                1. Install restic and rclone.
                2. Create an rclone remote named gdrive. Sign in as %2$s when the browser opens:

                   rclone config create gdrive drive scope=drive.readonly root_folder_id=%4$s

                3. Point restic at the repository:

                   export RESTIC_REPOSITORY='rclone:gdrive:%5$s'
                   export RESTIC_PASSWORD='%6$s'

                4. List the snapshots:

                   restic snapshots --no-lock

                5. Restore the newest snapshot into the folder restore:

                   restic restore latest --no-lock --target restore

                The restored folder holds the backup archives of the series. Restore them as deploy/README.md describes.
                """.formatted(
                        recovery.series(),
                        recovery.email(),
                        recovery.folderName(),
                        recovery.folderId(),
                        recovery.subpath(),
                        recovery.password());
    }
}
