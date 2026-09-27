/** The German search word, spelled out only here since this is the one file `language.test.ts` exempts. */
export const GERMAN_BACKUP_SYNONYM = "sicherung"

/** Words a person might type for a run's kind besides its own name; they are matched, never rendered. */
export const RUN_KIND_SEARCH_TERMS: Record<string, string[]> = {
  /** "archive" as on the static Backup page entry, so a backup run answers to the same words as the page. */
  BACKUP: ["backup", GERMAN_BACKUP_SYNONYM, "dump", "report", "archive"],
  UPDATE: ["update"],
  RESTART: ["restart"],
  DOWN: ["down", "take down", "put down", "stop", "stopped", "hold", "held"],
  START: ["start", "started", "up", "release"],
  REPORT: ["report"],
  APPLY: ["apply"],
}
