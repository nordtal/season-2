/**
 * Search synonyms for a run's kind - the words a person might type that are not the kind's own
 * name.
 *
 * steward/52: Till typed "report" wanting the backup that failed at 04:45, and the command palette
 * did not lead him there because it searched page titles only. A run's kind is one of the four
 * things a person would search a run by - the other three (its number, its outcome and its time)
 * carry no vocabulary problem and are built directly in `command-palette.tsx`. This file is where a
 * kind's vocabulary lives, and it is deliberately more than the kind's own label.
 *
 * **"sicherung" is German, on purpose.** Steward's interface is English - `NothingIsGermanTest` and
 * `language.test.ts` hold that line, because the bot is this project's bilingual half. A search
 * synonym is different in kind from a label or a comment: it is never rendered, only matched
 * against what somebody typed, so a German word here never puts German in front of anyone. That is
 * exactly why this file is the *only* file exempt from `language.test.ts`'s scan - see the `EXEMPT`
 * set there for the other half of this reasoning.
 * Till does not always type English, and the ticket asks explicitly for German search words to
 * reach an English page.
 *
 * {@link GERMAN_BACKUP_SYNONYM} exists so that nothing outside this file has to spell the German
 * word out again. Its own test and `command-palette.test.tsx` both import it rather than repeating
 * it, and that is what keeps *them* inside `language.test.ts`'s ordinary, unexempted scan - one
 * exempt file instead of three.
 */
export const GERMAN_BACKUP_SYNONYM = "sicherung"

export const RUN_KIND_SEARCH_TERMS: Record<string, string[]> = {
  // The four words steward/52 names by name, plus "archive" - already a synonym on the static
  // "Backup" page entry in navigation.ts, so a backup run answers to the same vocabulary as the
  // backup page does.
  BACKUP: ["backup", GERMAN_BACKUP_SYNONYM, "dump", "report", "archive"],
  UPDATE: ["update"],
  RESTART: ["restart"],
  DOWN: ["down", "take down", "put down", "stop", "stopped", "hold", "held"],
  START: ["start", "started", "up", "release"],
  REPORT: ["report"],
  APPLY: ["apply"],
}
