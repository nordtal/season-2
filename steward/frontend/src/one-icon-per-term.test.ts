import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { assert, describe, expect, it } from "vitest"

import { TERM_ICON } from "@/components/steward/term-icons"

const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)))

/** The one file that picks an icon for a term. */
const MAP = path.join("components", "steward", "term-icons.tsx")

const PHOSPHOR_IMPORT = /import\s*(?:type\s*)?\{([^}]*)\}\s*from\s*"@phosphor-icons\/react[^"]*"/g

function sourceFiles(directory: string): string[] {
  const found: string[] = []
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const full = path.join(directory, entry.name)
    if (entry.isDirectory()) {
      found.push(...sourceFiles(full))
    } else if (/\.tsx?$/.test(entry.name) && !entry.name.includes(".test.")) {
      found.push(full)
    }
  }
  return found
}

/** Returns every icon that a file imports from the pack, by its exported name. */
function iconsImportedBy(text: string): string[] {
  return [...text.matchAll(PHOSPHOR_IMPORT)].flatMap((match) =>
    match[1]
      .split(",")
      .map((name) => name.replace(/^\s*type\s+/, "").trim())
      .filter((name) => name !== ""),
  )
}

/** Returns the icons that the map holds, by the names its own imports give them. */
function mappedIcons(): Set<string> {
  return new Set(iconsImportedBy(readFileSync(path.join(source, MAP), "utf8")).filter((name) => name !== "Icon"))
}

function offenders(): string[] {
  const mapped = mappedIcons()
  const found: string[] = []
  for (const file of sourceFiles(source)) {
    const relative = path.relative(source, file)
    if (relative === MAP) continue
    for (const name of iconsImportedBy(readFileSync(file, "utf8"))) {
      if (mapped.has(name)) found.push(`${relative}  ${name}`)
    }
  }
  return found
}

describe("one icon per domain term", () => {
  it("has no file that imports an icon the term map already gives a term", () => {
    assert.deepEqual(
      offenders(),
      [],
      "These files pick an icon that belongs to a term in components/steward/term-icons.tsx. Draw the term" +
        " through TERM_ICON, or choose another icon where the meaning is not that term.",
    )
  })

  it("gives no two terms the same icon", () => {
    const seen = new Map<unknown, string>()
    const shared: string[] = []
    for (const [term, icon] of Object.entries(TERM_ICON)) {
      const other = seen.get(icon)
      if (other) shared.push(`${other} and ${term}`)
      seen.set(icon, term)
    }
    expect(shared).toEqual([])
  })

  /** Without this, a broken path or pattern would make the rule above pass by finding nothing. */
  it("actually reads the map and the sources", () => {
    expect(mappedIcons().size).toBe(Object.keys(TERM_ICON).length)
    const imported = sourceFiles(source).flatMap((file) => iconsImportedBy(readFileSync(file, "utf8")))
    expect(imported).toContain("PlusIcon")
    expect(imported.length).toBeGreaterThan(100)
  })
})
