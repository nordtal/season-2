-- A tie's winner is the event's actor, a reference to the member; the text it was written as goes with its column.
UPDATE hg_event event
SET actor_id = member.id
FROM team_member member
WHERE event.type = 'TIE'
  AND event.detail ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
  AND member.id = event.detail::uuid;

ALTER TABLE hg_event DROP COLUMN detail;
