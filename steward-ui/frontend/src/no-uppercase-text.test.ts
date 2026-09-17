import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { describe, expect, it } from "vitest"

const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)))

/**
 * steward/77's own last requirement, and the reason it exists is that the rule it holds has
 * already been broken once and accepted regardless.
 *
 * Till (via steward/77, 2026-09-14, restated 2026-09-16): caps text such as "LATEST ACTIONS" has
 * no place in this interface, except an abbreviation - CPU, RAM, 2FA - and those are written in
 * capitals as a word in the source, never forced there by a `text-transform`. On 2026-09-14 every
 * `uppercase` class was removed and the fix was accepted on `grep -rn "uppercase" src/` finding
 * nothing. On 2026-09-16 the same grep found one: `components/steward/panel.tsx`, a component
 * `steward/64` built the day after the sweep - a rule proven only by a one-off grep holds exactly
 * until the next file nobody thought to re-check.
 *
 * <h2>Why the class, and not the word</h2>
 * This reads every Tailwind class list in the sources - inside a plain `className="…"`, and
 * inside `cn(…)`/`cva(…)`, the two shapes `fits-on-a-phone.test.ts` learned the hard way are both
 * needed - and asks whether `uppercase` appears in one as a whole utility, not as a substring.
 * An abbreviation like CPU is a word written in a sentence, never a class, so this test never
 * looks at it and needs no hand-kept exception list for it. Only a class list that genuinely
 * forces a `text-transform: uppercase` can trip it.
 */
const UPPERCASE_UTILITY = /(^|[\s:])uppercase($|\s)/

/**
 * Every double-quoted string in a `.tsx` source that looks like a Tailwind class list: at least
 * one hyphenated utility, and no sentence punctuation - which is what tells a class list apart
 * from an ordinary piece of copy sitting in the same file. Comments are blanked first, because a
 * comment describing this very rule would otherwise spell out `uppercase` and trip on itself.
 */
function classAttributes(directory: string): Array<{ file: string; line: number; classes: string }> {
  const found: Array<{ file: string; line: number; classes: string }> = []
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const full = path.join(directory, entry.name)
    if (entry.isDirectory()) {
      found.push(...classAttributes(full))
      continue
    }
    if (!entry.name.endsWith(".tsx") || entry.name.includes(".test.")) continue
    // Blanked, not deleted - a multi-line block comment collapsed to one space would shift every
    // line number after it, which is exactly the kind of thing that makes a red result hard to
    // trust.
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

describe("nothing is drawn in capitals by a class (steward/77)", () => {
  it("has no uppercase utility in any class list", () => {
    expect(
      offenders(),
      "An `uppercase` utility forces caps regardless of what is typed. Till's rule (steward/77," +
        " 2026-09-16): caps text has no place here except an abbreviation, and an abbreviation is" +
        " a word in the source, not a class - so removing this is the whole fix, with no allowlist" +
        " needed for CPU, RAM or 2FA.\n\n" + offenders().join("\n"),
    ).toEqual([])
  })

  /**
   * Without this, a broken path or a changed extension makes the rule above pass by finding
   * nothing at all - the failure mode that leaves a green build and a guard nobody can trust. Same
   * shape as `identifiers-stay-in-the-popover.test.ts`'s second test.
   */
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
