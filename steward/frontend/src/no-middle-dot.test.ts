import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { assert, describe, expect, it } from "vitest"

const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)))

/** No middle dot as a separator anywhere in the interface; an ellipsis and an arrow are fine. */
const MIDDLE_DOT = /·/

/** Files allowed a middle dot, each with its reason; empty, and kept so the next one has a place. */
const EXEMPT = new Map<string, string>([])

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
  const found: string[] = []
  for (const file of sourceFiles(source)) {
    const relative = path.relative(source, file)
    if (EXEMPT.has(relative)) continue
    const text = readFileSync(file, "utf8")
    text.split("\n").forEach((line, index) => {
      if (MIDDLE_DOT.test(line)) {
        found.push(`${relative}:${index + 1}  ${line.trim().slice(0, 90)}`)
      }
    })
  }
  return found
}

describe("no middle dot as a separator", () => {
  it("has none in the pages and components this sweep covers", () => {
    assert.deepEqual(
      offenders(),
      [],
      "A middle dot showed up as a separator again. No separator character in the UI at all -" +
        " drop the second value, or give it its own place, but never another character in the" +
        " same spot.\n\n" +
        offenders().join("\n"),
    )
  })

  /** Without this, a broken path or extension makes the rule above pass by finding nothing. */
  it("actually reads the sources, so an empty result means something", () => {
    const files = sourceFiles(source)
    expect(files.length).toBeGreaterThan(30)
    expect(files.some((f) => f.endsWith("app/sign-in.tsx"))).toBe(true)
  })

  /** An exemption for a file that is already clean fails, or the list only ever grows. */
  it("has no exemption for a file that is already clean or gone", () => {
    const stale: string[] = []
    for (const [relative, reason] of EXEMPT) {
      const full = path.join(source, relative)
      let text: string
      try {
        text = readFileSync(full, "utf8")
      } catch {
        stale.push(`${relative} (${reason}) no longer exists`)
        continue
      }
      if (!MIDDLE_DOT.test(text)) {
        stale.push(`${relative} (${reason}) has no middle dot left - drop the exemption`)
      }
    }
    expect(stale).toEqual([])
  })
})
