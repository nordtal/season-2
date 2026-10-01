/**
 * Steward's backup file names, `<volume>-<stamp>.tar.zst` and `nordtal-<stamp>.dump`, taken apart.
 *
 * The report carries no file name, so the name is all that says what an archive holds.
 */
const VOLUME_ARCHIVE = /^(.+)-(\d{8}T\d{6}Z)\.tar\.zst(\.partial)?$/
const DATABASE_DUMP = /^(.+)-(\d{8}T\d{6}Z)\.dump(\.partial)?$/

export type Archived = { kind: "volume" | "database" | "unknown"; subject: string | null }

export function archived(name: string): Archived {
  const dump = DATABASE_DUMP.exec(name)
  /** The dump file is named after the database, not after `DatabaseDump.NAME`. */
  if (dump) return { kind: "database", subject: "database" }
  const volume = VOLUME_ARCHIVE.exec(name)
  if (volume) return { kind: "volume", subject: volume[1] }
  return { kind: "unknown", subject: null }
}
