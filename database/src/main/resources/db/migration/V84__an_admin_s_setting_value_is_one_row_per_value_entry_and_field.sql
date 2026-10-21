-- An admin's setting value is a tree of rows: a text, number, boolean or null, or a list or object whose entries
-- and fields are rows of their own. setting_override keeps who changed the path and when.
CREATE TABLE setting_override_node
(
    service  varchar(32)  NOT NULL,
    name     varchar(32)  NOT NULL,
    path     varchar(128) NOT NULL,
    -- Numbered in document order; 0 is the value itself.
    node     smallint     NOT NULL CONSTRAINT setting_override_node_node_check CHECK (node >= 0),
    parent   smallint,
    -- The name of an object's field, or the place of a list's entry, counted from 0.
    field    varchar(128),
    ordinal  smallint CONSTRAINT setting_override_node_ordinal_check CHECK (ordinal >= 0),
    kind     varchar(8)   NOT NULL
        CONSTRAINT setting_override_node_kind_check CHECK (kind IN ('STRING', 'NUMBER', 'BOOLEAN', 'NULL', 'LIST', 'OBJECT')),
    string   text,
    number   numeric,
    flag     boolean,
    CONSTRAINT setting_override_node_pkey PRIMARY KEY (service, name, path, node),
    CONSTRAINT setting_override_node_override_fkey FOREIGN KEY (service, name, path)
        REFERENCES setting_override (service, name, path) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT setting_override_node_parent_fkey FOREIGN KEY (service, name, path, parent)
        REFERENCES setting_override_node (service, name, path, node) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT setting_override_node_root_iff_no_parent CHECK ((node = 0) = (parent IS NULL)),
    CONSTRAINT setting_override_node_field_or_ordinal
        CHECK (parent IS NULL AND field IS NULL AND ordinal IS NULL
            OR parent IS NOT NULL AND (field IS NULL) <> (ordinal IS NULL)),
    CONSTRAINT setting_override_node_string_iff_string CHECK ((kind = 'STRING') = (string IS NOT NULL)),
    CONSTRAINT setting_override_node_number_iff_number CHECK ((kind = 'NUMBER') = (number IS NOT NULL)),
    CONSTRAINT setting_override_node_flag_iff_boolean CHECK ((kind = 'BOOLEAN') = (flag IS NOT NULL))
);
COMMENT ON TABLE setting_override_node IS 'Owned by steward: one value, list entry or object field of an admin''s setting. Every process reads its own rows and those of network.';

-- The rows of one value, in document order.
CREATE FUNCTION setting_override_nodes(value jsonb)
    RETURNS TABLE (node smallint, parent smallint, field text, ordinal smallint, kind text, string text,
                   number numeric, flag boolean)
    LANGUAGE sql IMMUTABLE AS $$
WITH RECURSIVE tree (address, field, ordinal, value) AS (
    SELECT ARRAY []::int[], NULL::text, NULL::int, value
    UNION ALL
    SELECT tree.address || child.place::int, child.field, child.ordinal, child.value
    FROM tree
    CROSS JOIN LATERAL (
        SELECT NULL::text AS field, (entry.place - 1)::int AS ordinal, entry.value, entry.place
        FROM jsonb_array_elements(CASE jsonb_typeof(tree.value) WHEN 'array' THEN tree.value ELSE '[]' END)
            WITH ORDINALITY AS entry (value, place)
        UNION ALL
        SELECT entry.key, NULL, entry.value, entry.place
        FROM jsonb_each(CASE jsonb_typeof(tree.value) WHEN 'object' THEN tree.value ELSE '{}' END)
            WITH ORDINALITY AS entry (key, value, place)
    ) AS child
),
numbered AS (
    SELECT row_number() OVER (ORDER BY address) - 1 AS node, * FROM tree
)
SELECT own.node::smallint, up.node::smallint, own.field, own.ordinal::smallint,
       CASE jsonb_typeof(own.value) WHEN 'array' THEN 'LIST' ELSE upper(jsonb_typeof(own.value)) END,
       CASE jsonb_typeof(own.value) WHEN 'string' THEN own.value #>> '{}' END,
       CASE jsonb_typeof(own.value) WHEN 'number' THEN (own.value #>> '{}')::numeric END,
       CASE jsonb_typeof(own.value) WHEN 'boolean' THEN (own.value #>> '{}')::boolean END
FROM numbered AS own
LEFT JOIN numbered AS up ON cardinality(own.address) > 0
    AND up.address = own.address[1:cardinality(own.address) - 1]
ORDER BY own.node
$$;

-- One node of a stored value with everything below it, as jsonb.
CREATE FUNCTION setting_override_value(service text, name text, path text, node smallint DEFAULT 0)
    RETURNS jsonb
    LANGUAGE plpgsql STABLE AS $$
DECLARE
    own setting_override_node;
BEGIN
    SELECT * INTO own FROM setting_override_node AS n
    WHERE n.service = $1 AND n.name = $2 AND n.path = $3 AND n.node = $4;
    RETURN CASE own.kind
        WHEN 'STRING' THEN to_jsonb(own.string)
        WHEN 'NUMBER' THEN to_jsonb(own.number)
        WHEN 'BOOLEAN' THEN to_jsonb(own.flag)
        WHEN 'NULL' THEN 'null'::jsonb
        WHEN 'LIST' THEN coalesce((
            SELECT jsonb_agg(setting_override_value($1, $2, $3, c.node) ORDER BY c.ordinal)
            FROM setting_override_node AS c
            WHERE c.service = $1 AND c.name = $2 AND c.path = $3 AND c.parent = $4), '[]')
        WHEN 'OBJECT' THEN coalesce((
            SELECT jsonb_object_agg(c.field, setting_override_value($1, $2, $3, c.node))
            FROM setting_override_node AS c
            WHERE c.service = $1 AND c.name = $2 AND c.path = $3 AND c.parent = $4), '{}')
    END;
END
$$;

INSERT INTO setting_override_node (service, name, path, node, parent, field, ordinal, kind, string, number, flag)
SELECT o.service, o.name, o.path, n.*
FROM setting_override AS o
CROSS JOIN LATERAL setting_override_nodes(o.value) AS n;

-- The release before this one reads value; the next one drops it.
ALTER TABLE setting_override ALTER COLUMN value DROP NOT NULL;
COMMENT ON COLUMN setting_override.value IS 'No longer written: setting_override_node holds the value.';
COMMENT ON TABLE setting_override IS 'Owned by steward, where an admin changes a setting and an update run sets the pack the proxy sends: one row per path whose value differs from its default, with its value in setting_override_node. Every process reads its own rows and those of network.';

GRANT SELECT ON setting_override_node TO ${role_read};
GRANT INSERT, DELETE ON setting_override_node TO ${role_steward_ui};
