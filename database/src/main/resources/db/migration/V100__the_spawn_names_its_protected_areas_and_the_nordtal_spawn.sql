-- The spawn's regions are its protected areas and its first join spawn is the Nordtal spawn. An admin's values
-- are copied to the new paths; the old rows stay until every server reads the new ones.
INSERT INTO setting_override (service, name, path, actor_kind, actor_id, changed)
SELECT service,
       name,
       CASE WHEN path = 'spawn-regions' THEN 'protected-areas'
            ELSE 'nordtal-spawn' || substr(path, length('first-join-spawn') + 1) END,
       actor_kind,
       actor_id,
       changed
FROM setting_override
WHERE service = 'smp'
  AND name = 'spawn'
  AND (path = 'spawn-regions' OR path = 'first-join-spawn' OR path LIKE 'first-join-spawn.%')
ON CONFLICT DO NOTHING;

INSERT INTO setting_override_node (service, name, path, node, parent, field, ordinal, kind, string, number, flag)
SELECT service,
       name,
       CASE WHEN path = 'spawn-regions' THEN 'protected-areas'
            ELSE 'nordtal-spawn' || substr(path, length('first-join-spawn') + 1) END,
       node,
       parent,
       field,
       ordinal,
       kind,
       string,
       number,
       flag
FROM setting_override_node
WHERE service = 'smp'
  AND name = 'spawn'
  AND (path = 'spawn-regions' OR path = 'first-join-spawn' OR path LIKE 'first-join-spawn.%')
ON CONFLICT DO NOTHING;
