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
 * <h2>Two more things, because on 2026-09-14 the derivation alone walked past four words</h2>
 * `Befehle`, `Konfiguration`, `Gelaufen` and `Sperre` were all on the screen with every test
 * green. Three of them are answered without adding a single word to a hand list:
 *
 * - **Stems, not whole words.** The bundle says `Befehl` and this said `Befehle`; the bundle says
 *   `Konfigurationsdateien` and this said `Konfiguration`. A derived list only ever knows the
 *   forms the bot happens to use, and German inflects and compounds, so a word counts as German
 *   when a derived word is a prefix of it or it is a prefix of a derived one.
 * - **Shape, not vocabulary.** `Gelaufen` is in no bundle and no stem of it is either. `shapes`
 *   in the rules file finds German by its endings, which works on words the bot has never said.
 *   `Verwerfen`, on the config form's discard button, was the same and cost a third pattern.
 *
 * **`Sperre` is the honest fourth**, and it is why the blind spot is written down rather than
 * papered over: a German word with no German ending, that the bot never says, cannot be derived
 * or recognised. It is in `extra`, by hand, which is what that list is for.
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

const ALSO_ENGLISH = new Set(rules.alsoEnglish.map((word) => word.toLowerCase()))

/**
 * Whether one word out of a source file is German - by stem, not only by equality.
 *
 * Two cases, and they are different mistakes. **Inflection**: the word is a derived one plus a
 * German ending, which is how `Befehle` got past a list holding `Befehl`. **Compounding**: a
 * derived word continues it, which is how `Konfiguration` got past a list holding
 * `Konfigurationsdateien`. The ending list is German-only on purpose - `stopped` is `stoppe` plus
 * a `d`, and `d` is not one of them.
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

/**
 * A word as a reader would see one, which an identifier is not.
 *
 * `_` and a digit count as part of the word - which is what `\b` did before this rule read
 * stems, and is what kept `NORDTAL_STEWARD_UI_CONFIG_DIR` quiet while `dir` sits in the German
 * bundle. Splitting on letters alone finds `DIR` in there, and nobody reads an environment
 * variable as a sentence.
 */
const WORD = /(?<![\wÄÖÜäöüß])[A-Za-zÄÖÜäöüß]{3,}(?![\wÄÖÜäöüß])/g

const SHAPES = rules.shapes.map((shape) => new RegExp(shape, "i"))

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

/** Derived once. It is a set of 450 words and this is asked of every line of every source file. */
const GERMAN = new Set(forbidden())

function germanWords(line: string): boolean {
  return (line.match(WORD) ?? []).some((word) => isGerman(word, GERMAN))
}

const hasGermanShape = (line: string) => SHAPES.some((shape) => shape.test(line))

describe("nothing in Steward is German", () => {
  it("derives its word list from the bot's own bundle, and it is not a short one", () => {
    // If this ever collapses to a handful, the bundle moved or the parse broke - and a guard that
    // silently stops guarding is worse than none, because the build stays green.
    expect(forbidden().length).toBeGreaterThan(300)
    expect(forbidden()).toContain("vergleich")
    expect(forbidden()).not.toContain("stand")
  })

  it("knows a German word by its stem, not only by the form the bot happens to use", () => {
    // The four that were on the screen on 2026-09-14 with every test green. Three are answered
    // here; `Sperre` is the one that cannot be, and it is in the rules file by hand.
    expect(isGerman("Befehle", GERMAN), "inflection: the bundle only says Befehl").toBe(true)
    expect(
      isGerman("Konfiguration", GERMAN),
      "compounding: the bundle only says Konfigurationsdateien",
    ).toBe(true)
    // The honest fourth: no stem of it is derivable and it has no German ending, so it is in the
    // hand list. If that line is ever removed this goes red, which is the point of asserting it.
    expect(isGerman("Sperre", GERMAN), "Sperre is in the rules file by hand").toBe(true)

    // And it still lets the language this interface is written in through. `started` and `stopped`
    // are the two that a looser rule flagged: they extend `starte` and `stoppe` by an English `d`.
    const english = ["Configuration", "Commands", "Status", "Backup", "Service", "Restore",
      "started", "stopped", "argument", "Operations"]
    for (const word of english) expect(isGerman(word, GERMAN), word).toBe(false)
  })

  it("knows German by its shape too, for words the bot has never said", () => {
    expect(hasGermanShape("label=\"Gelaufen\"")).toBe(true)
    expect(hasGermanShape("title=\"Einstellung\"")).toBe(true)
    expect(hasGermanShape("<Button>Verwerfen</Button>")).toBe(true)
    // The two that are deliberately reachable by no shape: `ge...t` participles share their shape
    // with `government`, and a guard that cries on `government` is a guard somebody deletes.
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
