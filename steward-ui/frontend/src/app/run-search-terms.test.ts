import { describe, expect, it } from "vitest"

import { GERMAN_BACKUP_SYNONYM, RUN_KIND_SEARCH_TERMS } from "@/app/run-search-terms"

/**
 * The word list itself, pinned down.
 *
 * `command-palette.test.tsx` proves the words actually reach the palette's search; this file
 * proves the words are the ones steward/52 names. It is **not** exempt from `language.test.ts` -
 * it imports the German word as a constant rather than spelling it out, which is the whole point of
 * that constant existing.
 */
describe("RUN_KIND_SEARCH_TERMS", () => {
  it("carries the four words steward/52 names on the backup entry", () => {
    for (const word of ["backup", GERMAN_BACKUP_SYNONYM, "dump", "report"]) {
      expect(RUN_KIND_SEARCH_TERMS.BACKUP).toContain(word)
    }
  })

  it("is German on the backup entry - Till does not always type English", () => {
    expect(GERMAN_BACKUP_SYNONYM).not.toBe("")
    expect(RUN_KIND_SEARCH_TERMS.BACKUP).toContain(GERMAN_BACKUP_SYNONYM)
  })
})
