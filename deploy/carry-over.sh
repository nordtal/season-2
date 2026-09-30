#!/bin/sh
#
# Carries the data of an older installation into a database this release has just migrated.
# It runs inside the postgres container, where psql and the local socket are:
#
#   docker exec -i nordtal-s2-postgres-1 sh -s restore_20260930T024500Z nordtal < deploy/carry-over.sh
#
# The first database is a restored dump of the old installation and is only read. The second has
# just been migrated and holds no data yet; everything is written to it in one transaction,
# and the script ends by comparing the row count of every carried table on both sides.
#
# Carried: access, links, the admin tree, playtime, the season, smp and Hunger Games data, the audit
# log, Steward's security keys and push subscriptions, the bot's posted messages, the payment
# gateway and its watermark, holds and added plugins, and a tab still to be made or cancelled as a request in the
# bank's inbox. Dropped: run and request history, metrics, sessions, link codes and who is online, which a fresh
# installation rebuilds or never needs.
set -eu

if [ "$#" -ne 2 ]; then
    echo "usage: carry-over.sh OLD_DATABASE NEW_DATABASE" >&2
    exit 2
fi
OLD="$1"
NEW="$2"
USER_NAME="${POSTGRES_USER:-postgres}"

psql_on() {
    database="$1"
    shift
    psql -X -q -v ON_ERROR_STOP=1 -U "$USER_NAME" -d "$database" "$@"
}

# One line per carried table, in foreign key order: the table, its columns, and optionally what is
# read from the old database: a select list over the table of the same name, or a whole query when
# the rows come from elsewhere. The counts compare the new table with what that read returns. A literal
# is dollar-quoted, since this list is one single-quoted string.
TABLES='discord_user|discord_id, locale, member_state, donor, updated, admin, discord_username, discord_username_updated, discord_display_name, discord_display_name_updated, discord_avatar_url, discord_avatar_url_updated, admin_granted_by, admin_granted_at, pack_exempt_by, pack_exempt_at|
account_link|discord_id, mc_uuid, linked, mc_name, mc_name_updated|
admin_grant|id, discord_id, granted_by, granted|
payment_request|id, reference, discord_id, days, amount_cents, donation_cents, status, bunq_tab_id, share_url, bunq_payment_id, created, expires, settled, tab_failed, tab_cancelled, matched_cents, matched_by|
bank_inbox|kind, payload, actor_kind|SELECT $$OPEN_TAB$$, jsonb_build_object($$payment$$, id), $$HOST$$ FROM payment_request WHERE status = $$OPEN$$ AND tab_requested IS NOT NULL AND bunq_tab_id IS NULL AND cancel_requested IS NULL UNION ALL SELECT $$CANCEL_TAB$$, jsonb_build_object($$payment$$, id), $$HOST$$ FROM payment_request WHERE cancel_requested IS NOT NULL AND bunq_tab_id IS NOT NULL AND tab_cancelled IS NULL
access_grant|id, discord_id, valid_from, valid_until, source, payment_request_id, revoked, created|
payment_notice|bunq_payment_id, reason, detail, reported, posted|
expiry_notice|discord_id, valid_until, kind, sent|
payment_gateway|id, state, watermark|SELECT true, (SELECT value FROM bot_setting WHERE key = $$payment.gateway$$ AND value IN ($$ON$$, $$OFF$$)), (SELECT value::timestamptz FROM bot_setting WHERE key = $$payment.watermark$$) WHERE EXISTS (SELECT 1 FROM bot_setting WHERE key IN ($$payment.gateway$$, $$payment.watermark$$))
season_phase|id, phase, updated, launch, smp_start|
player_playtime|discord_id, seconds, updated|
hg_game|id, state, started, ended, created, winner_member_id|id, state, started, ended, created, NULL
hg_team|id, game_id, name, colour_rgb, colour_named, created|
hg_member|id, team_id, game_id, discord_id, state, ready, created|
hg_event|id, game_id, type, actor_id, victim_id, detail, at|
smp_player|discord_id, aura, last_death_world, last_death_x, last_death_y, last_death_z, hg_winner_reward_granted, created, updated, welcome_shown|
smp_aura_event|id, discord_id, delta, reason, ref, at|
smp_milestone|key, state, unlocked|
smp_objective|id, milestone_key, key, type, amount, target, completed|
smp_contribution|objective_id, discord_id, amount, updated|
smp_grave|id, owner_id, world, x, y, z, contents, experience, created, looted, looted_by|
smp_poi|id, name, world, x, y, z, created_by, created|
smp_spin|discord_id, granted, used, last_free|
service_hold|service, since, actor_kind, actor_id, request_id|service, since, CASE WHEN held_by ~ $re$\(\d{17,20}\)\s*$$re$ THEN $$PERSON$$ WHEN held_by IS NULL OR held_by LIKE $$steward-worker%$$ THEN $$STEWARD$$ ELSE $$HOST$$ END, substring(held_by FROM $re$\((\d{17,20})\)\s*$$re$), NULL
service_plugin|service, artifact, project_id, file_prefix, title, icon_url, page_url, added, added_by|
audit_log|id, occurred, action, actor, subject, mc_uuid, detail|
managed_message|kind, channel_id, message_id, updated|
steward_credential|credential_id, discord_id, public_key, signature_count, label, transports, backup_eligible, backed_up, created_at, last_used_at|
steward_push_subscription|endpoint, discord_id, p256dh, auth, created_at, last_sent_at, device|
steward_push_preference|discord_id, alert_type, enabled, updated_at|'

# Personal data passes through this file, so it lives only as long as the script.
STREAM="$(mktemp)"
trap 'rm -f "$STREAM"' EXIT

{
    echo 'BEGIN;'
    # The schema seeds the phase; the old installation's row replaces it.
    echo 'DELETE FROM season_phase;'
} >"$STREAM"

# What a line reads from the old database.
old_query() {
    case "$3" in
        SELECT\ *) printf '%s' "$3" ;;
        '') printf 'SELECT %s FROM %s' "$2" "$1" ;;
        *) printf 'SELECT %s FROM %s' "$3" "$1" ;;
    esac
}

printf '%s\n' "$TABLES" | while IFS='|' read -r table columns select; do
    echo "COPY $table ($columns) FROM stdin;" >>"$STREAM"
    psql_on "$OLD" -c "COPY ($(old_query "$table" "$columns" "$select")) TO STDOUT" >>"$STREAM"
    printf '%s\n' '\.' >>"$STREAM"
done

# The game and its winning member point at each other, so the winner follows once both exist.
psql_on "$OLD" -At -c "SELECT format('UPDATE hg_game SET winner_member_id = %L WHERE id = %L;', winner_member_id, id)
                       FROM hg_game WHERE winner_member_id IS NOT NULL" >>"$STREAM"
{
    echo "SELECT setval(pg_get_serial_sequence('admin_grant', 'id'), coalesce(max(id), 0) + 1, false) FROM admin_grant;"
    echo 'COMMIT;'
} >>"$STREAM"

psql_on "$NEW" -f "$STREAM" >/dev/null

printf '%-28s %10s %10s\n' table old new
failed=0
printf '%s\n' "$TABLES" | {
    while IFS='|' read -r table columns select; do
        old_count="$(psql_on "$OLD" -At -c "SELECT count(*) FROM ($(old_query "$table" "$columns" "$select")) AS carried")"
        new_count="$(psql_on "$NEW" -At -c "SELECT count(*) FROM $table")"
        printf '%-28s %10s %10s\n' "$table" "$old_count" "$new_count"
        [ "$old_count" = "$new_count" ] || failed=1
    done
    if [ "$failed" -ne 0 ]; then
        echo "carry-over: a table's count differs, see above" >&2
        exit 1
    fi
}
