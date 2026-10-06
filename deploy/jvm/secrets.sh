# Sourced by entrypoint.sh: the secrets only this service reads, from the file compose mounts for it alone.
# deploy/nordtal.sh writes the file as NAME=value lines, mode 600 and owned by the service's uid, so
# compose never interpolates these values and steward-agent never mounts them.

# Where compose.yml mounts the service's own directory of NORDTAL_SECRETS_DIR.
SECRETS_DIRECTORY=/app/secrets

# Exports every NAME=value line of $1, read as data and never run as shell; one layer of quotes is
# taken off, as compose reads an env file. A missing file under a mounted directory is said on stderr.
read_secrets() {
    file="$1"
    if [ ! -f "$file" ]; then
        if [ -d "$(dirname "$file")" ]; then
            echo "[entrypoint] $file is not there, so this service starts without its own secrets" >&2
        fi
        return 0
    fi
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            '' | '#'*) continue ;;
        esac
        line="${line#export }"
        name="${line%%=*}"
        value="${line#*=}"
        case "$name" in
            "$line" | '' | [0-9]* | *[!A-Za-z0-9_]*)
                # The line is not repeated, since it may hold a secret.
                echo "[entrypoint] a line of $file is no NAME=value assignment and is skipped" >&2
                continue
                ;;
        esac
        case "$value" in
            \"*\")
                value="${value#\"}"
                value="${value%\"}"
                ;;
            \'*\')
                value="${value#\'}"
                value="${value%\'}"
                ;;
        esac
        export "$name=$value"
    done <"$file"
}
