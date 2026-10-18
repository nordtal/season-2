import { describe, expect, it } from "vitest"

import { GERMAN_BACKUP_SYNONYM, RUN_KIND_SEARCH_TERMS } from "@/app/run-search-terms"

/** `language.test.ts` checks this file too, so the German word is imported as a constant. */
describe("RUN_KIND_SEARCH_TERMS", () => {
  it("carries the four intended words on the backup entry", () => {
    for (const word of ["backup", GERMAN_BACKUP_SYNONYM, "dump", "report"]) {
      expect(RUN_KIND_SEARCH_TERMS.BACKUP).toContain(word)
    }
  })

  it("is German on the backup entry - not everybody types English", () => {
    expect(GERMAN_BACKUP_SYNONYM).not.toBe("")
    expect(RUN_KIND_SEARCH_TERMS.BACKUP).toContain(GERMAN_BACKUP_SYNONYM)
  })
})
