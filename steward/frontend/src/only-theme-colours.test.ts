import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { assert, describe, expect, it } from "vitest"

const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)))

/** The theme: the only files that name a colour of their own. */
const THEME = new Set(["index.css", "brand.gen.css"])

/** A colour named outside the theme: a hex or functional value, a white or black utility, or Tailwind's palette. */
const OWN_COLOUR = [
  /#[0-9a-fA-F]{3,8}\b/,
  /\b(?:rgba?|hsla?|hwb|lab|lch|oklab|oklch)\(/,
  /\b(?:bg|text|border|ring|fill|stroke|outline|shadow|from|via|to|divide|decoration|caret|accent|placeholder)-(?:white|black)\b/,
  /-(?:slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-\d{2,3}\b/,
]

/** Files whose colours are content, not interface, each with what it draws. */
const EXEMPT = new Map<string, string>([
  ["lib/rich-text.ts", "Minecraft's chat colours"],
  ["components/steward/message-preview.tsx", "Minecraft's white, the colour of unstyled game text"],
  ["components/steward/message-editor/preview.tsx", "the game's and Discord's own surfaces in the previews"],
  ["components/steward/message-editor/visual-editor.tsx", "a text as the game or Discord draws it"],
  ["components/steward/message-editor/colour-menu.tsx", "a colour a person picks for a text"],
  ["components/steward/colour-control.tsx", "a colour a person picks, on Minecraft's chat background"],
])

function sourceFiles(directory: string): string[] {
  const found: string[] = []
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const full = path.join(directory, entry.name)
    if (entry.isDirectory()) {
      found.push(...sourceFiles(full))
    } else if (/\.(tsx?|css)$/.test(entry.name) && !/\.(test|fixtures)\./.test(entry.name)) {
      found.push(full)
    }
  }
  return found
}

function namesAColour(line: string): boolean {
  return OWN_COLOUR.some((pattern) => pattern.test(line))
}

function offenders(): string[] {
  const found: string[] = []
  for (const file of sourceFiles(source)) {
    const relative = path.relative(source, file)
    if (THEME.has(relative) || EXEMPT.has(relative)) continue
    readFileSync(file, "utf8")
      .split("\n")
      .forEach((line, index) => {
        if (namesAColour(line)) found.push(`${relative}:${index + 1}  ${line.trim().slice(0, 100)}`)
      })
  }
  return found
}

describe("only the theme's colours", () => {
  it("has no file outside the theme that names a colour of its own", () => {
    assert.deepEqual(
      offenders(),
      [],
      "These lines name a colour outside index.css and brand.gen.css. Use a theme variable, adding one to" +
        " index.css where none fits; only content (a game or Discord colour, a picked colour) is exempt.",
    )
  })

  /** An exemption for a file that names no colour any more fails, or the list only ever grows. */
  it("has no exemption for a file that is clean or gone", () => {
    const stale: string[] = []
    for (const [relative, reason] of EXEMPT) {
      let text: string
      try {
        text = readFileSync(path.join(source, relative), "utf8")
      } catch {
        stale.push(`${relative} (${reason}) does not exist`)
        continue
      }
      if (!text.split("\n").some(namesAColour)) stale.push(`${relative} (${reason}) names no colour`)
    }
    expect(stale).toEqual([])
  })

  /** Without this, a broken path or pattern makes the rule above pass by finding nothing. */
  it("actually reads the sources and finds the theme's colours", () => {
    expect(sourceFiles(source).length).toBeGreaterThan(100)
    expect(
      readFileSync(path.join(source, "index.css"), "utf8").split("\n").filter(namesAColour).length,
    ).toBeGreaterThan(10)
  })
})
