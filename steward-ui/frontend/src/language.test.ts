import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { describe, expect, it } from "vitest"

/**
 * Nothing in Steward is German.
 *
 * The rule is the project's, not this file's: the interface, its comments and the files it writes
 * are English, and the bot is the bilingual half - that is what its language configuration is for.
 *
 * <h2>The word list is derived, and that is the whole design</h2>
 * This guard was written on 2026-09-14 around a hand-kept list of about sixty words. The next day
 * it had missed **nine** German strings that a person could read on screen: `Abschicken` on the
 * console's button, `Vergleich` as a column head, `Zuletzt ermittelt`, `Stufen` and `Was dabei
 * passiert` as card titles, `Gestartet` and `Stand` as figures, `Aktuell` on the season page and
 * `Leere Liste.` in the configuration form. A list of sixty words is not a rule; it is a memory of
 * the sixty words that had already been noticed once.
 *
 * So the list has a source instead of an opinion. `commands/.../de.properties` is a corpus of real
 * German this project maintains anyway - every word in it that is **not** also in `en.properties`
 * is forbidden here. That filter is what keeps `Server`, `Status` and `Discord` out of the way, and
 * it means the guard grows whenever the bot's German does, with nobody having to remember.
 *
 * What a derivation cannot know is in `steward-ui/language-rules.json`: the words that are German
 * in the bundle and English here (`die`, `stand`, `spawn`, `tag`), the words that leaked and are
 * not in the bundle at all (`Ampel`, `Strg`, `Vergleich`), and the abbreviations, which are not
 * words and so have no boundary to match on. That file is read by this test and by
 * `NothingIsGermanTest`, which enforces the same rule on the Java half - one rule, one file.
 *
 * An umlaut and an ß need no list at all: there is no English word with one, and every name in
 * this repository is spelt without.
 *
 * <b>This file is not scanned.</b> Neither is the rules file - both are lists of German words.
 */

const here = path.dirname(fileURLToPath(import.meta.url))
const frontend = path.resolve(here, "..")
const repository = path.resolve(frontend, "../..")
const bundle = path.join(repository, "commands/src/main/resources/messages/commands")

type Rules = { alsoEnglish: string[]; extra: string[]; abbreviations: string[] }

const rules: Rules = JSON.parse(
  readFileSync(path.join(repository, "steward-ui/language-rules.json"), "utf8"),
)

/** Every word of three letters or more in the *values* of a message bundle - never in its keys. */
function bundleWords(file: string): Set<string> {
  const words = new Set<string>()
  for (const line of readFileSync(path.join(bundle, file), "utf8").split("\n")) {
    const separator = line.indexOf("=")
    if (separator === -1 || line.trimStart().startsWith("#")) continue
    for (const word of line.slice(separator + 1).match(/[A-Za-zÄÖÜäöüß]{3,}/g) ?? []) {
      words.add(word.toLowerCase())
    }
  }
  return words
}

function forbidden(): string[] {
  const english = bundleWords("en.properties")
  const allowed = new Set(rules.alsoEnglish.map((word) => word.toLowerCase()))
  const words = new Set<string>()
  for (const word of bundleWords("de.properties")) {
    if (!english.has(word) && !allowed.has(word)) words.add(word)
  }
  for (const word of rules.extra) {
    if (!allowed.has(word.toLowerCase())) words.add(word.toLowerCase())
  }
  return [...words].sort()
}

const GERMAN = new RegExp(
  `\\b(${forbidden().map((word) => word.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|")})\\b`,
  "i",
)

const ABBREVIATION = new RegExp(`(${rules.abbreviations.join("|")})`)

const NON_ENGLISH_LETTERS = /[äöüÄÖÜß]/

/** Everything a reader of this interface can reach: its sources, its page and its build file. */
function scanned(): string[] {
  const files: string[] = [
    path.join(frontend, "index.html"),
    path.join(frontend, "vite.config.ts"),
  ]
  const walk = (directory: string) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const full = path.join(directory, entry.name)
      if (entry.isDirectory()) {
        walk(full)
      } else if (/\.(ts|tsx|css|html)$/.test(entry.name) && full !== path.join(here, "language.test.ts")) {
        files.push(full)
      }
    }
  }
  walk(path.join(frontend, "src"))
  return files
}

function offences(pattern: RegExp): string[] {
  const found: string[] = []
  for (const file of scanned()) {
    readFileSync(file, "utf8")
      .split("\n")
      .forEach((line, index) => {
        if (pattern.test(line)) {
          found.push(`${path.relative(frontend, file)}:${index + 1}: ${line.trim()}`)
        }
      })
  }
  return found
}

describe("nothing in Steward is German", () => {
  it("derives its word list from the bot's own bundle, and it is not a short one", () => {
    // If this ever collapses to a handful, the bundle moved or the parse broke - and a guard that
    // silently stops guarding is worse than none, because the build stays green.
    expect(forbidden().length).toBeGreaterThan(300)
    expect(forbidden()).toContain("vergleich")
    expect(forbidden()).not.toContain("stand")
  })

  it("has no German word in any source file", () => {
    expect(offences(GERMAN)).toEqual([])
  })

  it("has no German abbreviation either", () => {
    expect(offences(ABBREVIATION)).toEqual([])
  })

  it("has no umlaut and no ß anywhere", () => {
    expect(offences(NON_ENGLISH_LETTERS)).toEqual([])
  })

  it("says English on the document, because that is what a screen reader reads", () => {
    const index = readFileSync(path.join(frontend, "index.html"), "utf8")
    expect(index).toMatch(/<html lang="en"/)
  })
})
