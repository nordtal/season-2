/**
 * The German search word. It is written out only in this file, which `language.test.ts` exempts.
 */
export const GERMAN_BACKUP_SYNONYM = "sicherung"

/**
 * Words a person might type for a run's kind besides its name. They are matched and never shown.
 */
export const RUN_KIND_SEARCH_TERMS: Record<string, string[]> = {
  /**
   * "archive" is also a search word of the static Backup page entry, so a backup run and the page answer to the same words.
   */
  BACKUP: ["backup", GERMAN_BACKUP_SYNONYM, "dump", "report", "archive"],
  UPDATE: ["update"],
  RESTART: ["restart"],
  DOWN: ["down", "take down", "put down", "stop", "stopped", "hold", "held"],
  START: ["start", "started", "up", "release"],
}
