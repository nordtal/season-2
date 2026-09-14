import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { describe, expect, it } from "vitest"

/**
 * Nothing in Steward is German.
 *
 * The rule is the project's, not this file's: the interface, its comments and the files it writes
 * are English, and the bot is the bilingual half - that is what its language configuration is for.
 * It was carried out by hand once and it drifted, which is what a rule with no test does. What was
 * still German on 2026-09-14, months after the change: `Strg` in the sidebar footer and on the
 * 404 page, half a German sentence around it (`Mit ... finds every page`), `lang="de"` on the
 * document itself - so every screen reader announced an English interface in German - and `Ampel`
 * and `Lauf` in comments, one of which is copied into `steward-ui.yml` for an operator to read.
 *
 * **The word list is deliberately small.** It is the words that actually appeared here plus the
 * ones nearest to them, not a dictionary: a guard that reports a false positive once gets deleted,
 * and then there is no guard. An umlaut or an ß is its own case and needs no list - there is no
 * English word with one, and every name in this repository is spelt without.
 *
 * <b>This file is not scanned</b>, for the obvious reason that it is a list of German words. It is
 * the one exception, and it is why the list lives in exactly one place.
 */
const GERMAN = new RegExp(
  "\\b(" +
    [
      // The words that were actually still here on 2026-09-14.
      "Strg", "Ampel", "Lauf", "Läufe", "umgezogen", "Faktor", "zweiten",
      // German function words, which is what a whole sentence is made of. Every one of these was
      // checked against English first: `die`, `war`, `man` and `mit` are English words too and are
      // deliberately not in this list, because one false positive is what gets a guard deleted.
      "gibt", "kein", "keine", "keinen", "keinem", "keiner", "nicht", "nichts",
      "und", "oder", "wird", "werden", "sind", "wurde", "wurden",
      "ein", "eine", "einen", "einem", "eines", "der", "den", "dem", "des", "das",
      "diese", "dieser", "dieses", "auch", "aber", "noch", "schon", "immer", "jetzt",
      "sehr", "wenn", "weil", "dass", "damit", "bereits", "für",
      // The words an interface reaches for first.
      "Abbrechen", "Löschen", "Zurück", "Übersicht", "Einstellungen", "Anmelden",
      "Willkommen", "Fehler", "Datei", "Seite", "Passwort", "Benutzer", "Anmeldung",
    ].join("|") +
    ")\\b",
)

const NON_ENGLISH_LETTERS = /[äöüÄÖÜß]/

const here = path.dirname(fileURLToPath(import.meta.url))
const frontend = path.resolve(here, "..")

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
  it("has no German word in any source file", () => {
    expect(offences(GERMAN)).toEqual([])
  })

  it("has no umlaut and no ß anywhere", () => {
    expect(offences(NON_ENGLISH_LETTERS)).toEqual([])
  })

  it("says English on the document, because that is what a screen reader reads", () => {
    const index = readFileSync(path.join(frontend, "index.html"), "utf8")
    expect(index).toMatch(/<html lang="en"/)
  })
})
