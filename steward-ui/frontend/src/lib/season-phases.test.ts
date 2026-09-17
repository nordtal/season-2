import { readFileSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { describe, expect, it } from "vitest"

import { SEASON_PHASES } from "@/lib/season-phases"

/**
 * steward/90's own guard: the direction that matters is not "every real phase has a label" - it is
 * "nothing here names a phase that is not real". The defect this ticket fixes was exactly that:
 * `overview.tsx` carried a second list with two invented phases (`EVENT`, `ENDED`) that this test
 * would have failed on the day they were typed.
 *
 * `SeasonPhase.java` is the truth, and it is Java - this file cannot import it, so it reads the
 * source text directly, the same way `app/the-header-is-gone.test.ts` reads `.tsx` sources it
 * cannot render. A second, hand-typed copy of the five names here would just be the bug moved one
 * file over: this test only means something because the list of real names comes from the file
 * itself, not from typing `PRE_LAUNCH` a third time.
 */
const here = path.dirname(fileURLToPath(import.meta.url))
const javaSource = path.join(
  here,
  "../../../../common/src/main/java/eu/nordtal/s2/common/SeasonPhase.java",
)

/** Every enum constant `SeasonPhase.java` declares, read off the source rather than remembered. */
function realPhaseNames(): string[] {
  const text = readFileSync(javaSource, "utf8")
    // Blank every javadoc block whole - a constant's own doc comment can mention another constant
    // by name (START_EVENT's does, of PRE_EVENT), and that must not count as a declaration.
    .replace(/\/\*[\s\S]*?\*\//g, " ")

  const bodyStart = text.indexOf("enum SeasonPhase")
  if (bodyStart === -1) {
    throw new Error(`${javaSource} no longer declares "enum SeasonPhase" - has it moved or renamed?`)
  }
  const braceAt = text.indexOf("{", bodyStart)
  // The constant list ends at the first ";" after the opening brace - true as long as no constant
  // carries a body of its own (`SMP { ... }`), which none of these five do.
  const semicolonAt = text.indexOf(";", braceAt)
  const constantsBlock = text.slice(braceAt + 1, semicolonAt)

  return Array.from(constantsBlock.matchAll(/\b[A-Z][A-Z0-9_]*\b/g)).map((match) => match[0])
}

describe("season-phases.ts against SeasonPhase.java (steward/90)", () => {
  it("actually read the enum and found names, so an empty list means the parser broke", () => {
    const names = realPhaseNames()
    // Not a hardcoded five: this only has to prove the parser found something real, so a change to
    // SeasonPhase.java's shape (a rename, a moved file) fails loudly here rather than as a silent
    // empty list that would make every guard below vacuously pass.
    expect(names.length).toBeGreaterThan(0)
    expect(names).toContain("PRE_LAUNCH")
    expect(names).toContain("MAINTENANCE")
  })

  it("has a label for every phase the backend actually has", () => {
    const real = new Set(realPhaseNames())
    // `Set<string>`, not `Set<SeasonPhaseName>`: the whole point of this file is to compare the
    // typed list against names read out of a Java file at run time, and those are plain strings.
    // Letting the narrower type win here is what `vitest run` cannot see and `tsc -b` can.
    const listed = new Set<string>(SEASON_PHASES.map((phase) => phase.name))
    const missing = [...real].filter((name) => !listed.has(name))

    expect(
      missing,
      "SeasonPhase.java declares a phase with no entry in SEASON_PHASES, so it would reach the" +
        " screen as its own bare constant name.",
    ).toEqual([])
  })

  it("names no phase the backend does not have - the direction that actually catches an invented one", () => {
    const real = new Set(realPhaseNames())
    const invented = SEASON_PHASES.map((phase) => phase.name).filter((name) => !real.has(name))

    expect(
      invented,
      "SEASON_PHASES lists a phase SeasonPhase.java does not declare - exactly the steward/90" +
        " defect (EVENT, ENDED), invented rather than read off the backend.",
    ).toEqual([])
  })
})
