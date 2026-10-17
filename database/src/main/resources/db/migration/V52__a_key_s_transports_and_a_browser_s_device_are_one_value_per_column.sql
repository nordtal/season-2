-- A key's transports are a row each, a browser's device name its system and its browser, and the roles held at
-- sign-in, which nothing read, are gone.
CREATE TABLE steward_credential_transport
(
    credential_id bytea NOT NULL
        CONSTRAINT steward_credential_transport_credential_fkey
            REFERENCES steward_credential (credential_id) ON DELETE CASCADE,
    -- The WebAuthn transport's own name, such as usb, nfc or internal, kept as the browser said it.
    transport     text  NOT NULL CONSTRAINT steward_credential_transport_transport_check CHECK (transport <> ''),
    CONSTRAINT steward_credential_transport_pkey PRIMARY KEY (credential_id, transport)
);
COMMENT ON TABLE steward_credential_transport IS 'Owned by steward-ui: one way a registered security key said it can be reached, handed back to the browser at sign-in.';
GRANT SELECT, INSERT, UPDATE, DELETE ON steward_credential_transport TO ${role_steward_ui};

INSERT INTO steward_credential_transport (credential_id, transport)
SELECT DISTINCT credential_id, btrim(transport)
FROM steward_credential, unnest(string_to_array(transports, ',')) AS transport
WHERE btrim(transport) <> '';

ALTER TABLE steward_credential DROP COLUMN transports;

-- Devices named the system first, then the browser, either one alone where the other was not recognised.
ALTER TABLE steward_push_subscription
    ADD COLUMN system  text,
    ADD COLUMN browser text;
COMMENT ON COLUMN steward_push_subscription.system IS 'The operating system read from the User-Agent, such as iPhone or Linux; NULL where none was recognised.';
COMMENT ON COLUMN steward_push_subscription.browser IS 'The browser read from the User-Agent, such as Firefox; NULL where none was recognised.';

UPDATE steward_push_subscription
SET system  = CASE
                  WHEN split_part(device, ', ', 1) IN ('iPhone', 'iPad', 'Android', 'Windows', 'Mac', 'Linux')
                      THEN split_part(device, ', ', 1) END,
    browser = CASE
                  WHEN device LIKE '%, %' THEN split_part(device, ', ', 2)
                  WHEN device NOT IN ('iPhone', 'iPad', 'Android', 'Windows', 'Mac', 'Linux') THEN device END
WHERE device IS NOT NULL;

ALTER TABLE steward_push_subscription DROP COLUMN device;

ALTER TABLE steward_session DROP COLUMN roles;
