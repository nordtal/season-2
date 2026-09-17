import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { describe, expect, it } from "vitest"

const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)))

/**
 * No middle dot ("·") as a separator anywhere in the interface.
 *
 * Till, 2026-09-16: a middle dot should generally not be used in the UI. Typography should not
 * carry a text symbol for a separator - an icon, if anything - and no separator character of that
 * kind is preferred at all. Asked about an em dash instead: an em dash is not bad by itself, but
 * nowhere in this app should a text be long enough to need one. An ellipsis ("Waiting for the
 * key…") and an arrow are unaffected - both were explicitly not part of that answer.
 *
 * steward/78 removed the six that sat on the three sign-in pages and the shell's stuck-door
 * screen (`sign-in.tsx`, `hold-key.tsx`, `security-key.tsx`, `shell.tsx`). A sweep of the rest of
 * `src/` on the same day, while this guard was being built, turned up 23 more, spread over six
 * files neither that ticket nor Till's 2026-09-16 walkthrough looked at - steward/84 tracked
 * removing them and cleared all six on 2026-09-16. `pages/status.tsx`'s one dot (the CPU tile's
 * hint line, "N cores · Load X") was steward/80's own find, and steward/80 dropped the load
 * number from the tile entirely rather than keep the dot - so `EXEMPT` below is empty: there is no
 * middle dot left anywhere under `src/` for this guard to name an owner for.
 */
const MIDDLE_DOT = /·/

/**
 * Pre-existing dots, each with the ticket that owned removing it - empty now that steward/78,
 * steward/80 and steward/84 have all cleared theirs (2026-09-16). The exemption is by file, not by
 * line: a file half-swept looks identical to one this test never read, which is the same failure
 * mode `identifiers-stay-in-the-popover.test.ts`'s `ALLOWED` and `SoundVocabularyTest.ALLOWED`
 * both guard against. Left as a typed, empty map rather than deleted, so the next dot has
 * somewhere to be listed rather than a structure to reinvent.
 */
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

describe("no middle dot as a separator, outside the files still tracked for a sweep (steward/78)", () => {
  it("has none in the pages and components this sweep covers", () => {
    expect(
      offenders(),
      "A middle dot showed up as a separator again. Till's rule (2026-09-16): no separator" +
        " character in the UI at all - drop the second value, or give it its own place, but never" +
        " another character in the same spot.\n\n" + offenders().join("\n"),
    ).toEqual([])
  })

  /**
   * Without this, a broken path or a changed extension makes the rule above pass by finding
   * nothing at all - the failure mode that leaves a green build and a guard nobody can trust. Same
   * shape as `identifiers-stay-in-the-popover.test.ts`'s second test.
   */
  it("actually reads the sources, so an empty result means something", () => {
    const files = sourceFiles(source)
    expect(files.length).toBeGreaterThan(30)
    expect(files.some((f) => f.endsWith("app/sign-in.tsx"))).toBe(true)
  })

  /**
   * Mirrors `SoundVocabularyTest.theAllowlistHasNoGhosts`: an `EXEMPT` entry for a file that no
   * longer has a middle dot is a promise nobody is keeping any more, and steward/84 marks a file
   * done by deleting its line here - if this test does not check for that, the list only ever
   * grows.
   */
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
