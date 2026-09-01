# The database backup sidecar.
#
# FROM the same PostgreSQL image the server runs, and for one reason that is not cosmetic:
# pg_dump refuses a server newer than itself. Pinning this image to `postgres` and the server to
# something else is the way to get a backup that stops working on the day the server is upgraded,
# silently, in the direction where you find out at the restore. `POSTGRES_IMAGE` in .env drives
# both through compose's build arg.
ARG POSTGRES_IMAGE=postgres:17-alpine
FROM ${POSTGRES_IMAGE}

COPY backup.sh /usr/local/bin/backup
RUN chmod +x /usr/local/bin/backup

# The dump directory has to exist IN THE IMAGE and be owned by `postgres` before the volume is
# mounted over it. Docker seeds an empty named volume from whatever is at that path in the image,
# ownership included - and creates it owned by root when the path is not there. Found by running
# it: without this line the first dump fails with "Permission denied" on its own output file, and
# it fails identically every night afterwards.
RUN mkdir -p /dumps && chown postgres:postgres /dumps

# The image's own entrypoint initialises a data directory and starts a server. This container is a
# client and must do neither, so it is bypassed entirely.
ENTRYPOINT []

# Runs as the image's `postgres` user rather than root: this container needs to read a database and
# write one directory, and nothing it does wants root.
USER postgres

CMD ["/usr/local/bin/backup"]
