-- steward raises an alert when the resource pack the proxy offers does not answer.
ALTER TABLE admin_alert DROP CONSTRAINT admin_alert_type_check;
ALTER TABLE admin_alert
    ADD CONSTRAINT admin_alert_type_check
        CHECK (type IN ('SERVICE', 'BACKUP', 'DISK', 'MEMORY', 'DRIFT', 'PACK', 'RUN', 'PAYMENT', 'BOT'));
