import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { assert, describe, expect, it } from "vitest"

const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)))

/** No class list may force `uppercase`; an abbreviation like CPU is written in capitals in the source instead. */
const UPPERCASE_UTILITY = /(^|[\s:])uppercase($|\s)/

/** Every `.tsx` string shaped like a class list, comments blanked so this rule cannot trip on itself. */
function classAttributes(directory: string): Array<{ file: string; line: number; classes: string }> {
  const found: Array<{ file: string; line: number; classes: string }> = []
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const full = path.join(directory, entry.name)
    if (entry.isDirectory()) {
      found.push(...classAttributes(full))
      continue
    }
    if (!entry.name.endsWith(".tsx") || entry.name.includes(".test.")) continue
    /** Blanked, not deleted, so line numbers stay true. */
    const withoutComments = readFileSync(full, "utf8")
      .replace(/\/\*[\s\S]*?\*\//g, (comment) => comment.replace(/[^\n]/g, " "))
      .replace(/^\s*\/\/.*$/gm, " ")
    const relative = path.relative(source, full)
    withoutComments.split("\n").forEach((lineText, index) => {
      for (const match of lineText.matchAll(/"([^"\n]*)"/g)) {
        const classes = match[1]
        if (!/-/.test(classes) || /[.,;?!]\s|[.,;?!]$/.test(classes)) continue
        found.push({ file: relative, line: index + 1, classes })
      }
    })
  }
  return found
}

function offenders(): string[] {
  return classAttributes(source)
    .filter(({ classes }) => UPPERCASE_UTILITY.test(` ${classes} `))
    .map(({ file, line, classes }) => `${file}:${line}  "${classes}"`)
}

describe("nothing is drawn in capitals by a class", () => {
  it("has no uppercase utility in any class list", () => {
    assert.deepEqual(
      offenders(),
      [],
      "An `uppercase` utility forces caps regardless of what is typed. Caps text has no place" +
        " here except an abbreviation, and an abbreviation is a word in the source, not a class -" +
        " so removing this is the whole fix, with no allowlist needed for CPU, RAM or 2FA.\n\n" +
        offenders().join("\n"),
    )
  })

  /** Without this, a broken path or extension makes the rule above pass by finding nothing. */
  it("actually reads the sources, so an empty result means something", () => {
    const files: string[] = []
    const walk = (directory: string) => {
      for (const entry of readdirSync(directory, { withFileTypes: true })) {
        const full = path.join(directory, entry.name)
        if (entry.isDirectory()) walk(full)
        else if (entry.name.endsWith(".tsx") && !entry.name.includes(".test.")) files.push(full)
      }
    }
    walk(source)
    expect(files.length).toBeGreaterThan(30)
    expect(files.some((f) => f.endsWith("components/steward/panel.tsx"))).toBe(true)
  })
})
