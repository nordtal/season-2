-- A resolve step names an out-of-date image as pulled, since an update run always pulls it.
-- The UPDATE corrects the resolve steps written before.
UPDATE run_change SET image = 'PULLED'
WHERE artefact = 'image' AND image IS NULL AND step IN (SELECT id FROM run_step WHERE step = 'RESOLVE');
