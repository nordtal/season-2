-- A request of the bank, the SMP or the Hunger Games is its inbox row and one row of its kind's table, a column per
-- argument, where its arguments were one JSON payload. The previews keep their payload until they follow.
CREATE TABLE bank_open_tab
(
    request bigint PRIMARY KEY CONSTRAINT bank_open_tab_request_fkey REFERENCES bank_inbox (id) ON DELETE CASCADE,
    payment uuid   NOT NULL CONSTRAINT bank_open_tab_payment_fkey REFERENCES payment_request (id)
);
COMMENT ON TABLE bank_open_tab IS 'Owned by steward, which claims the bank inbox: the payment request an OPEN_TAB row of bank_inbox makes a tab for.';
CREATE INDEX bank_open_tab_payment ON bank_open_tab (payment);

CREATE TABLE bank_cancel_tab
(
    request bigint PRIMARY KEY CONSTRAINT bank_cancel_tab_request_fkey REFERENCES bank_inbox (id) ON DELETE CASCADE,
    payment uuid   NOT NULL CONSTRAINT bank_cancel_tab_payment_fkey REFERENCES payment_request (id)
);
COMMENT ON TABLE bank_cancel_tab IS 'Owned by steward, which claims the bank inbox: the payment request a CANCEL_TAB row of bank_inbox takes the tab of.';
CREATE INDEX bank_cancel_tab_payment ON bank_cancel_tab (payment);

CREATE TABLE smp_complete_objective
(
    request bigint PRIMARY KEY
        CONSTRAINT smp_complete_objective_request_fkey REFERENCES smp_inbox (id) ON DELETE CASCADE,
    -- An objective's key is unique within its milestone only; the one asked for is of the active milestone.
    key     text   NOT NULL
);
COMMENT ON TABLE smp_complete_objective IS 'Owned by smp, which claims its inbox: the objective of the active milestone a COMPLETE_OBJECTIVE row of smp_inbox closes.';

CREATE TABLE smp_unlock_milestone
(
    request bigint PRIMARY KEY
        CONSTRAINT smp_unlock_milestone_request_fkey REFERENCES smp_inbox (id) ON DELETE CASCADE,
    key     text   NOT NULL CONSTRAINT smp_unlock_milestone_key_fkey REFERENCES smp_milestone (key)
);
COMMENT ON TABLE smp_unlock_milestone IS 'Owned by smp, which claims its inbox: the milestone an UNLOCK_MILESTONE row of smp_inbox unlocks.';
CREATE INDEX smp_unlock_milestone_key ON smp_unlock_milestone (key);

CREATE TABLE hunger_games_start_game
(
    request   bigint  PRIMARY KEY
        CONSTRAINT hunger_games_start_game_request_fkey REFERENCES hunger_games_inbox (id) ON DELETE CASCADE,
    -- Whether the asker has seen the numbers, which a start below the recommended minimum needs.
    confirmed boolean NOT NULL
);
COMMENT ON TABLE hunger_games_start_game IS 'Owned by hunger-games, which claims its inbox: how a START_GAME row of hunger_games_inbox was asked for.';

-- Whoever asks an inbox writes its kind's row; the consumer reads it, as every service may.
GRANT SELECT ON bank_open_tab, bank_cancel_tab, smp_complete_objective, smp_unlock_milestone, hunger_games_start_game
    TO ${role_read};
GRANT INSERT ON bank_open_tab, bank_cancel_tab TO ${role_discord_bot}, ${role_steward_ui};
GRANT INSERT ON smp_complete_objective, smp_unlock_milestone, hunger_games_start_game TO ${role_steward_ui};

-- A kind with a row of its own leaves the payload empty.
ALTER TABLE smp_inbox ALTER COLUMN payload DROP NOT NULL;
ALTER TABLE hunger_games_inbox ALTER COLUMN payload DROP NOT NULL;
