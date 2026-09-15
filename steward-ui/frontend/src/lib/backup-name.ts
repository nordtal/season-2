/**
 * How the worker names what it writes into the backup directory.
 *
 * `TarSnapshots`: `<volume>-<stamp>.tar.zst`, and `<…>.partial` while it is being written.
 * `DatabaseDump`: `nordtal-<stamp>.dump`. Nothing in the run's report carries the file name, so the
 * name is the only thing that says what an archive holds - which is why it is taken apart rather
 * than printed as one string.
 *
 * **It lives here rather than in `operations.tsx` because the traffic light needs it too.** Before
 * steward/40 the start page classified `/backups` by asking whether a name ended in `.dump`, which
 * made every other file in the directory a volume archive - an `.unverified` mark, a `README.txt`,
 * anything an operator dropped there. The page that lists the directory already knew better, and
 * two answers to "what is this file" is one answer that is wrong somewhere.
 */
const VOLUME_ARCHIVE = /^(.+)-(\d{8}T\d{6}Z)\.tar\.zst(\.partial)?$/
const DATABASE_DUMP = /^(.+)-(\d{8}T\d{6}Z)\.dump(\.partial)?$/

export type Archived = { kind: "volume" | "database" | "unknown"; subject: string | null }

export function archived(name: string): Archived {
  const dump = DATABASE_DUMP.exec(name)
  // `DatabaseDump.NAME` is the word the report's line carries for the dump; the file is named after
  // the database, not after that word.
  if (dump) return { kind: "database", subject: "database" }
  const volume = VOLUME_ARCHIVE.exec(name)
  if (volume) return { kind: "volume", subject: volume[1] }
  return { kind: "unknown", subject: null }
}
