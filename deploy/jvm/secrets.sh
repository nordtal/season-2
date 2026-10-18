# Sourced by entrypoint.sh. It exports the secrets that only this service reads, from the file that
# compose mounts for this service alone. deploy/nordtal.sh writes the file as NAME=value lines with
# mode 600 and the uid of the service, so compose never interpolates the values and steward-agent never mounts them.

# The directory of the service in NORDTAL_SECRETS_DIR, where compose.yml mounts it.
SECRETS_DIRECTORY=/app/secrets

# Exports every NAME=value line of $1. The function reads the lines as data and never runs them as
# shell, and it removes one layer of quotes, as compose does for an env file. A missing file under a
# mounted directory is reported on stderr.
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
                # The message does not repeat the line, because the line may hold a secret.
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
