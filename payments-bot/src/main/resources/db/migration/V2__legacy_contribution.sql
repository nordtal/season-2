-- TEMPORARY - delete this file in stage B, together with the code that uses it.
--
-- Stage A rewrote V1 into the season 2 access schema, in which the `contribution` table does not
-- exist. The season 1 code that reads it (Contribution, ContributionDao, ContributionRepository,
-- ContributionService and the Discord listeners on top of them) is explicitly out of stage A's
-- scope and is replaced wholesale in stage B. Dropping the table now would leave that code
-- compiling but broken at runtime, and would turn ContributionRepositoryIntegrationTest red.
--
-- So the table survives here, in a file of its own, so that stage B removes it with a single
-- `rm` rather than by editing V1 again. It is not part of the access schema and nothing in
-- docs/access-system.md refers to it.
--
-- Verbatim from the pre-2026-08-30 V1__contribution.sql.

CREATE TABLE contribution
(
    id               uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    receiver_id      varchar(32)    NOT NULL,
    euro_amount      numeric(10, 2) NOT NULL,
    created          timestamp      NOT NULL,
    duration_seconds bigint         NOT NULL,
    bunq_payment_id  bigint         NOT NULL
);

CREATE INDEX contribution_receiver_id_idx ON contribution (receiver_id);

CREATE UNIQUE INDEX contribution_bunq_payment_id_key
    ON contribution (bunq_payment_id)
    WHERE bunq_payment_id >= 0;
