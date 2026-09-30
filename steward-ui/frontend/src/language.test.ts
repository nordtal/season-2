import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { assert, describe, expect, it } from "vitest"

/**
 * Nothing in Steward is German; this file and the rules file are lists of German words and are not scanned.
 *
 * Forbidden: words in `de.properties` but not `en.properties`, by stem, plus `language-rules.json`.
 */

const here = path.dirname(fileURLToPath(import.meta.url))
const frontend = path.resolve(here, "..")
const repository = path.resolve(frontend, "../..")
const bundle = path.join(repository, "discord-bot/src/main/resources/messages/access")

type Rules = {
  alsoEnglish: string[]
  extra: string[]
  abbreviations: string[]
  minStem: number
  minPrefix: number
  minRest: number
  tails: string[]
  shapes: string[]
}

const rules: Rules = JSON.parse(readFileSync(path.join(repository, "steward-ui/language-rules.json"), "utf8"))

/** Every word of three letters or more in a message bundle's values, never its keys. */
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
  return [...words].toSorted()
}

const ALSO_ENGLISH = new Set(rules.alsoEnglish.map((word) => word.toLowerCase()))

/**
 * Whether a word is German by stem: a derived word plus a German ending, or a prefix of a derived compound.
 *
 * The endings are German only, so `stopped` is not `stoppe` plus `d`.
 */
export function isGerman(word: string, german: Set<string>): boolean {
  const lower = word.toLowerCase()
  if (ALSO_ENGLISH.has(lower)) return false
  if (german.has(lower)) return true
  for (const known of german) {
    if (known.length >= rules.minStem && lower.startsWith(known)) {
      if (rules.tails.includes(lower.slice(known.length))) return true
    }
    if (lower.length >= rules.minPrefix && known.startsWith(lower)) {
      if (known.length - lower.length >= rules.minRest) return true
    }
  }
  return false
}

/** A word as a reader sees one: `_` and digits join it, so `NORDTAL_STEWARD_UI_CONFIG_DIR` is not `dir`. */
const WORD = /(?<![\wÄÖÜäöüß])[A-Za-zÄÖÜäöüß]{3,}(?![\wÄÖÜäöüß])/g

const SHAPES = rules.shapes.map((shape) => new RegExp(shape, "i"))

const ABBREVIATION = new RegExp(`(${rules.abbreviations.join("|")})`)

const NON_ENGLISH_LETTERS = /[äöüÄÖÜß]/

/**
 * The exemptions: this file and the palette's search synonyms, which are typed, never printed.
 *
 * Everything else imports the German synonym from there, so it stays inside the scan.
 */
const EXEMPT = new Set([path.join(here, "language.test.ts"), path.join(frontend, "src", "app", "run-search-terms.ts")])

/** Everything a reader of this interface can reach: its sources, its page and its build file. */
function scanned(): string[] {
  const files: string[] = [path.join(frontend, "index.html"), path.join(frontend, "vite.config.ts")]
  const walk = (directory: string) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const full = path.join(directory, entry.name)
      if (entry.isDirectory()) {
        walk(full)
      } else if (/\.(ts|tsx|css|html)$/.test(entry.name) && !EXEMPT.has(full)) {
        files.push(full)
      }
    }
  }
  walk(path.join(frontend, "src"))
  return files
}

function offences(guilty: (line: string) => boolean): string[] {
  const found: string[] = []
  for (const file of scanned()) {
    readFileSync(file, "utf8")
      .split("\n")
      .forEach((line, index) => {
        if (guilty(line)) {
          found.push(`${path.relative(frontend, file)}:${index + 1}: ${line.trim()}`)
        }
      })
  }
  return found
}

const matching = (pattern: RegExp) => (line: string) => pattern.test(line)

/** Derived once, since it is asked of every line of every source file. */
const GERMAN = new Set(forbidden())

function germanWords(line: string): boolean {
  return (line.match(WORD) ?? []).some((word) => isGerman(word, GERMAN))
}

const hasGermanShape = (line: string) => SHAPES.some((shape) => shape.test(line))

describe("nothing in Steward is German", () => {
  it("derives its word list from the bot's own bundle, and it is not a short one", () => {
    /** A collapse to a handful means the bundle moved or the parse broke, and the guard stopped guarding. */
    expect(forbidden().length).toBeGreaterThan(200)
    expect(forbidden()).toContain("vergleich")
    expect(forbidden()).not.toContain("stand")
  })

  it("knows a German word by its stem, not only by the form the bot happens to use", () => {
    /** Inflection, compounding and shape, the three ways a derived list is extended. */
    expect(isGerman("Befehle", GERMAN), "inflection: the list only says Befehl").toBe(true)
    expect(isGerman("Konfiguration", GERMAN), "compounding: the list only says Konfigurationsdateien").toBe(true)
    /** No stem or ending derives it, so it is in the rules file by hand. */
    expect(isGerman("Sperre", GERMAN), "Sperre is in the rules file by hand").toBe(true)

    /** English still passes: `started` and `stopped` extend `starte` and `stoppe` by an English `d`. */
    const english = [
      "Configuration",
      "Commands",
      "Status",
      "Backup",
      "Service",
      "Restore",
      "started",
      "stopped",
      "argument",
      "Operations",
    ]
    for (const word of english) assert.strictEqual(isGerman(word, GERMAN), false, word)
  })

  it("knows German by its shape too, for words the bot has never said", () => {
    expect(hasGermanShape('label="Gelaufen"')).toBe(true)
    expect(hasGermanShape('title="Einstellung"')).toBe(true)
    expect(hasGermanShape("<Button>Verwerfen</Button>")).toBe(true)
    /** `ge...t` participles are left alone, since they share their shape with `government`. */
    expect(hasGermanShape("const government = readableNumber(x)")).toBe(false)
    expect(hasGermanShape("<span>the gentlest version</span>")).toBe(false)
  })

  it("has no German word in any source file", () => {
    expect(offences(germanWords)).toEqual([])
  })

  it("has nothing with a German ending either", () => {
    expect(offences(hasGermanShape)).toEqual([])
  })

  it("has no German abbreviation either", () => {
    expect(offences(matching(ABBREVIATION))).toEqual([])
  })

  it("has no umlaut and no ß anywhere", () => {
    expect(offences(matching(NON_ENGLISH_LETTERS))).toEqual([])
  })

  it("says English on the document, because that is what a screen reader reads", () => {
    const index = readFileSync(path.join(frontend, "index.html"), "utf8")
    expect(index).toMatch(/<html lang="en"/)
  })
})
