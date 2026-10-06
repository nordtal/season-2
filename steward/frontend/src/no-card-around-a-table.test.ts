import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { assert, describe, expect, it } from "vitest"

const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)))

/** A `steward-table` row is a bordered card on a phone, so the table never sits in a `Card`: a `Panel` holds it. */
const CARD = /from "@\/components\/ui\/card"/
const TABLE = /steward-table/

function sourceFiles(directory: string): string[] {
  const found: string[] = []
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const full = path.join(directory, entry.name)
    if (entry.isDirectory()) {
      found.push(...sourceFiles(full))
    } else if (entry.name.endsWith(".tsx") && !entry.name.includes(".test.")) {
      found.push(full)
    }
  }
  return found
}

function offenders(): string[] {
  return sourceFiles(source)
    .filter((file) => {
      const text = readFileSync(file, "utf8")
      return CARD.test(text) && TABLE.test(text)
    })
    .map((file) => path.relative(source, file))
}

describe("no bordered card around a phone table", () => {
  it("has no file that imports Card and draws a steward-table", () => {
    assert.deepEqual(
      offenders(),
      [],
      "These files put a steward-table inside a Card, so on a phone every row is a bordered card in a" +
        " bordered card. Hold the table in a Panel instead.",
    )
  })

  /** Without this, a broken path or pattern makes the rule above pass by finding nothing. */
  it("actually finds both halves in the sources", () => {
    const files = sourceFiles(source).map((file) => readFileSync(file, "utf8"))
    expect(files.filter((text) => TABLE.test(text)).length).toBeGreaterThan(3)
    expect(files.filter((text) => CARD.test(text)).length).toBeGreaterThan(3)
  })
})
