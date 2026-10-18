-- An editor's rebuild of a packaged Discord layout is rows: its parts as a tree, each text block's lines, the texts and
-- link buttons the editor added with one row per language, and the images an admin uploaded. The texts of declared keys
-- stay in message_override. Every process re-reads on nordtal_messages.
CREATE TABLE layout_image
(
    id         bigserial    CONSTRAINT layout_image_pkey PRIMARY KEY,
    media_type varchar(16)  NOT NULL
        CONSTRAINT layout_image_media_type_check CHECK (media_type IN ('image/png', 'image/jpeg', 'image/gif', 'image/webp')),
    bytes      bytea        NOT NULL,
    sha256     char(64)     NOT NULL GENERATED ALWAYS AS (encode(sha256(bytes), 'hex')) STORED
        CONSTRAINT layout_image_sha256_key UNIQUE,
    size       integer      NOT NULL GENERATED ALWAYS AS (octet_length(bytes)) STORED,
    actor_kind varchar(16)  NOT NULL
        CONSTRAINT layout_image_actor_kind_check CHECK (actor_kind IN ('PERSON', 'STEWARD', 'HOST')),
    actor_id   varchar(32),
    uploaded   timestamptz  NOT NULL,
    CONSTRAINT layout_image_actor_id_iff_person CHECK ((actor_kind = 'PERSON') = (actor_id IS NOT NULL))
);
COMMENT ON TABLE layout_image IS 'Owned by steward, where an admin uploads it: one image a rebuilt layout shows as upload://<id>. steward serves it to the browser, discord-bot sends it as an attachment.';

CREATE TABLE layout_override
(
    bundle     varchar(32)  NOT NULL CONSTRAINT layout_override_bundle_check CHECK (bundle ~ '^[a-z][a-z0-9-]*$'),
    layout     varchar(64)  NOT NULL CONSTRAINT layout_override_layout_check CHECK (layout ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    -- The SHA-256 of the packaged layout the rebuild was written over, in hex; a release that changes it changes this.
    replaced   char(64)     NOT NULL CONSTRAINT layout_override_replaced_check CHECK (replaced ~ '^[0-9a-f]{64}$'),
    actor_kind varchar(16)  NOT NULL
        CONSTRAINT layout_override_actor_kind_check CHECK (actor_kind IN ('PERSON', 'STEWARD', 'HOST')),
    actor_id   varchar(32),
    changed    timestamptz  NOT NULL,
    CONSTRAINT layout_override_pkey PRIMARY KEY (bundle, layout),
    CONSTRAINT layout_override_actor_id_iff_person CHECK ((actor_kind = 'PERSON') = (actor_id IS NOT NULL))
);
COMMENT ON TABLE layout_override IS 'Owned by steward, where an admin rebuilds a Discord layout: one row per rebuilt layout of a bundle. Every process reads the rows of the bundles it loads.';

CREATE TABLE layout_part
(
    bundle  varchar(32) NOT NULL,
    layout  varchar(64) NOT NULL,
    -- The part's place in the tree read top to bottom, each part before the parts it holds, from zero.
    part    smallint    NOT NULL CONSTRAINT layout_part_part_check CHECK (part >= 0),
    -- The part that holds it, null for a part at the top of the layout.
    parent  smallint,
    type    varchar(16) NOT NULL
        CONSTRAINT layout_part_type_check
            CHECK (type IN ('CONTAINER', 'TEXT', 'SECTION', 'SEPARATOR', 'GALLERY', 'ROW', 'LANGUAGES')),
    accent  char(7)     CONSTRAINT layout_part_accent_check CHECK (accent ~ '^#[0-9a-f]{6}$'),
    -- A section's button, or the element of a block per language.
    element varchar(64),
    CONSTRAINT layout_part_pkey PRIMARY KEY (bundle, layout, part),
    CONSTRAINT layout_part_layout_fkey FOREIGN KEY (bundle, layout)
        REFERENCES layout_override (bundle, layout) ON DELETE CASCADE,
    CONSTRAINT layout_part_parent_fkey FOREIGN KEY (bundle, layout, parent)
        REFERENCES layout_part (bundle, layout, part) ON DELETE CASCADE,
    CONSTRAINT layout_part_parent_check CHECK (parent < part)
);
COMMENT ON TABLE layout_part IS 'Owned by steward: one part of a rebuilt layout, a Components V2 component.';

CREATE TABLE layout_line
(
    bundle varchar(32)  NOT NULL,
    layout varchar(64)  NOT NULL,
    part   smallint     NOT NULL,
    line   smallint     NOT NULL CONSTRAINT layout_line_line_check CHECK (line >= 0),
    -- A declared text as bundle/key; null with list for a text the editor added, whose texts are layout_line_text.
    key    varchar(160),
    list   varchar(64),
    style  varchar(8)   CONSTRAINT layout_line_style_check CHECK (style IN ('TITLE', 'HEADING')),
    CONSTRAINT layout_line_pkey PRIMARY KEY (bundle, layout, part, line),
    CONSTRAINT layout_line_part_fkey FOREIGN KEY (bundle, layout, part)
        REFERENCES layout_part (bundle, layout, part) ON DELETE CASCADE,
    CONSTRAINT layout_line_one_source CHECK (num_nonnulls(key, list) <= 1)
);
COMMENT ON TABLE layout_line IS 'Owned by steward: one line of a text block of a rebuilt layout, top to bottom.';

CREATE TABLE layout_line_text
(
    bundle   varchar(32) NOT NULL,
    layout   varchar(64) NOT NULL,
    part     smallint    NOT NULL,
    line     smallint    NOT NULL,
    language varchar(16) NOT NULL CONSTRAINT layout_line_text_language_check CHECK (language ~ '^[a-z]{2,3}$'),
    text     text        NOT NULL,
    CONSTRAINT layout_line_text_pkey PRIMARY KEY (bundle, layout, part, line, language),
    CONSTRAINT layout_line_text_line_fkey FOREIGN KEY (bundle, layout, part, line)
        REFERENCES layout_line (bundle, layout, part, line) ON DELETE CASCADE
);
COMMENT ON TABLE layout_line_text IS 'Owned by steward: a text the editor added to a rebuilt layout, in one language.';

CREATE TABLE layout_row_element
(
    bundle   varchar(32) NOT NULL,
    layout   varchar(64) NOT NULL,
    part     smallint    NOT NULL,
    position smallint    NOT NULL CONSTRAINT layout_row_element_position_check CHECK (position >= 0),
    element  varchar(64) NOT NULL,
    CONSTRAINT layout_row_element_pkey PRIMARY KEY (bundle, layout, part, position),
    CONSTRAINT layout_row_element_part_fkey FOREIGN KEY (bundle, layout, part)
        REFERENCES layout_part (bundle, layout, part) ON DELETE CASCADE
);
COMMENT ON TABLE layout_row_element IS 'Owned by steward: a button or select an action row of a rebuilt layout holds, left to right.';

CREATE TABLE layout_media
(
    bundle   varchar(32) NOT NULL,
    layout   varchar(64) NOT NULL,
    part     smallint    NOT NULL,
    position smallint    NOT NULL CONSTRAINT layout_media_position_check CHECK (position >= 0),
    -- An attachment:// name the code sends or an https URL; null for an uploaded image.
    address  text        CONSTRAINT layout_media_address_check CHECK (address ~ '^(attachment|https)://'),
    image    bigint      CONSTRAINT layout_media_image_fkey REFERENCES layout_image (id),
    CONSTRAINT layout_media_pkey PRIMARY KEY (bundle, layout, part, position),
    CONSTRAINT layout_media_part_fkey FOREIGN KEY (bundle, layout, part)
        REFERENCES layout_part (bundle, layout, part) ON DELETE CASCADE,
    CONSTRAINT layout_media_one_source CHECK (num_nonnulls(address, image) = 1)
);
COMMENT ON TABLE layout_media IS 'Owned by steward: an image of a gallery, or a section''s thumbnail, of a rebuilt layout.';

CREATE TABLE layout_link
(
    bundle  varchar(32) NOT NULL,
    layout  varchar(64) NOT NULL,
    element varchar(64) NOT NULL,
    url     text        NOT NULL CONSTRAINT layout_link_url_check CHECK (url ~ '^https://\S+$'),
    CONSTRAINT layout_link_pkey PRIMARY KEY (bundle, layout, element),
    CONSTRAINT layout_link_layout_fkey FOREIGN KEY (bundle, layout)
        REFERENCES layout_override (bundle, layout) ON DELETE CASCADE
);
COMMENT ON TABLE layout_link IS 'Owned by steward: a link button the editor added to a rebuilt layout, placed by its element id.';

CREATE TABLE layout_link_label
(
    bundle   varchar(32) NOT NULL,
    layout   varchar(64) NOT NULL,
    element  varchar(64) NOT NULL,
    language varchar(16) NOT NULL CONSTRAINT layout_link_label_language_check CHECK (language ~ '^[a-z]{2,3}$'),
    label    text        NOT NULL,
    CONSTRAINT layout_link_label_pkey PRIMARY KEY (bundle, layout, element, language),
    CONSTRAINT layout_link_label_link_fkey FOREIGN KEY (bundle, layout, element)
        REFERENCES layout_link (bundle, layout, element) ON DELETE CASCADE
);
COMMENT ON TABLE layout_link_label IS 'Owned by steward: the label of an added link button in one language.';

GRANT SELECT ON layout_image, layout_override, layout_part, layout_line, layout_line_text, layout_row_element,
    layout_media, layout_link, layout_link_label TO ${role_read};
GRANT INSERT, UPDATE, DELETE ON layout_image, layout_override, layout_part, layout_line, layout_line_text,
    layout_row_element, layout_media, layout_link, layout_link_label TO ${role_steward_ui};
GRANT USAGE ON SEQUENCE layout_image_id_seq TO ${role_steward_ui};
