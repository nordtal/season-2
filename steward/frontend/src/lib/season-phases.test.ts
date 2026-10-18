import { readFileSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { assert, describe, expect, it } from "vitest"

import { SEASON_PHASES } from "@/lib/season-phases"

/** The names are read from `SeasonPhase.java`'s source text. */
const here = path.dirname(fileURLToPath(import.meta.url))
const javaSource = path.join(here, "../../../../common/src/main/java/eu/nordtal/season/common/SeasonPhase.java")

function realPhaseNames(): string[] {
  const text = readFileSync(javaSource, "utf8")
    /** A constant's doc can name another constant. */
    .replace(/\/\*[\s\S]*?\*\//g, " ")

  const bodyStart = text.indexOf("enum SeasonPhase")
  if (bodyStart === -1) {
    throw new Error(`${javaSource} no longer declares "enum SeasonPhase" - has it moved or renamed?`)
  }
  const braceAt = text.indexOf("{", bodyStart)
  const semicolonAt = text.indexOf(";", braceAt)
  const constantsBlock = text.slice(braceAt + 1, semicolonAt)

  return Array.from(constantsBlock.matchAll(/\b[A-Z][A-Z0-9_]*\b/g)).map((match) => match[0])
}

describe("season-phases.ts against SeasonPhase.java", () => {
  it("actually read the enum and found names, so an empty list means the parser broke", () => {
    const names = realPhaseNames()
    /** So a moved `SeasonPhase.java` fails here and does not pass empty. */
    expect(names.length).toBeGreaterThan(0)
    expect(names).toContain("PRE_LAUNCH")
    expect(names).toContain("MAINTENANCE")
  })

  it("has a label for every phase the backend actually has", () => {
    const real = new Set(realPhaseNames())
    /** `tsc -b` rejects the narrower type of names read at run time. */
    const listed = new Set<string>(SEASON_PHASES.map((phase) => phase.name))
    const missing = [...real].filter((name) => !listed.has(name))

    assert.deepEqual(
      missing,
      [],
      "SeasonPhase.java declares a phase with no entry in SEASON_PHASES, so it would reach the" +
        " screen as its own bare constant name.",
    )
  })

  it("names no phase the backend does not have - the direction that actually catches an invented one", () => {
    const real = new Set(realPhaseNames())
    const invented = SEASON_PHASES.map((phase) => phase.name).filter((name) => !real.has(name))

    assert.deepEqual(
      invented,
      [],
      "SEASON_PHASES lists a phase SeasonPhase.java does not declare, invented rather than read off the backend.",
    )
  })
})
