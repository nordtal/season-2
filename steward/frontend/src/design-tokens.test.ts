import { readdirSync, readFileSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { assert, describe, expect, it } from "vitest"

// The project fills in these three. Everything below is the kit's and stays as it is.

/** The folder of the sources, relative to this file. */
const SOURCE = path.dirname(fileURLToPath(import.meta.url))

/** The stylesheets that define the tokens. The test skips them. */
const THEME = new Set(["index.css", "brand.gen.css"])

/** `<file>: <class>` of each arbitrary value that is content, not design, with what it draws. */
const EXEMPT = new Map<string, string>([
  ["components/steward/message-editor/preview.tsx: leading-[1.375]", "Discord's line height in a message's preview"],
  ["components/steward/message-editor/preview.tsx: max-w-[364px]", "the game's boss bar at its own width"],
  ["components/steward/message-editor/preview.tsx: max-w-[400px]", "Discord's select menu at its own width"],
  ["components/steward/message-editor/preview.tsx: max-w-[432px]", "Discord's embed at its own width"],
  ["components/steward/message-editor/preview.tsx: max-w-[440px]", "Discord's modal at its own width"],
  ["components/steward/message-editor/preview.tsx: max-w-[610px]", "the game's server list entry at its own width"],
  ["components/steward/message-editor/preview.tsx: rounded-[1px]", "the corners of the game's boss bar"],
  ["components/steward/message-editor/preview.tsx: rounded-[2px]", "the corners of the game's tooltip and server icon"],
  [
    "components/steward/message-editor/preview.tsx: rounded-[3px]",
    "the corners of a Discord mention and a modal's input",
  ],
  [
    "components/steward/message-editor/preview.tsx: rounded-[4px]",
    "the corners of Discord's code, select menu, embed, channel and command",
  ],
  ["components/steward/message-editor/preview.tsx: rounded-[8px]", "the corners of Discord's button"],
  ["components/steward/message-editor/preview.tsx: text-[0.6em]", "a missing glyph's name inside the game's text"],
  ["components/steward/message-editor/preview.tsx: text-[0.85em]", "Discord's inline code"],
  ["components/steward/message-editor/preview.tsx: text-[15px]", "Discord's message text"],
  ["components/steward/message-editor/document-preview.tsx: bg-[#8B8B8B]", "the game's grey slot behind an item label"],
  ["components/steward/message-editor/document-preview.tsx: m-[2px]", "the game's inventory slot border"],
  ["components/steward/message-editor/document-preview.tsx: max-h-[400px]", "Discord's media at its own height"],
  [
    "components/steward/message-editor/document-preview.tsx: max-w-[324px]",
    "the game's inventory grid at its own width",
  ],
  ["components/steward/message-editor/document-preview.tsx: max-w-[480px]", "Discord's media gallery at its own width"],
  ["components/steward/message-editor/document-preview.tsx: px-[2px]", "the game's inventory slot padding"],
  ["components/steward/message-editor/document-preview.tsx: rounded-[2px]", "the corners of the game's item label"],
  [
    "components/steward/message-editor/document-preview.tsx: rounded-[3px]",
    "the corners of a Discord mention and a modal's input",
  ],
  ["components/steward/message-editor/document-preview.tsx: rounded-[4px]", "the corners of Discord's media"],
  ["components/steward/message-editor/document-preview.tsx: rounded-[8px]", "the corners of Discord's container"],
  [
    "components/steward/message-editor/document-preview.tsx: text-[#404040]",
    "the game's dark grey text of an inventory slot's name",
  ],
  [
    "components/steward/message-editor/document-preview.tsx: text-[#949ba4]",
    "Discord's muted text of a small line and a missing file",
  ],
  ["components/steward/message-editor/document-preview.tsx: text-[11px]", "the game's slot name in an inventory"],
  [
    "components/steward/message-editor/visual-editor.tsx: leading-[1.375]",
    "Discord's line height while a Discord text is edited",
  ],
  [
    "components/steward/message-editor/visual-editor.tsx: text-[15px]",
    "Discord's text size while a Discord text is edited",
  ],
])

/**
 * The spacing steps of padding, margin, gap and space, in units of `--spacing` (0.25rem): 1px, every 2px up
 * to 12px, every 4px up to 24px, every 8px up to 48px, then 64, 80, 96 and 128px.
 */
const SPACING_STEPS = new Set("0 px 0.5 1 1.5 2 2.5 3 4 5 6 8 10 12 16 20 24 32".split(" "))

/** Utilities whose value is a token: spacing, sizes, colours, radii, type, depth and layering. */
const TOKEN_UTILITY =
  /(?<![\w-])-?(p[xytrblse]?|m[xytrblse]?|gap(?:-[xy])?|space-[xy]|inset(?:-[xy])?|top|right|bottom|left|start|end|w|h|size|min-[wh]|max-[wh]|basis|indent|translate-[xy]|scroll-[mp][xytrblse]?|text|bg|border(?:-[xytrblse])?|outline(?:-offset)?|ring(?:-offset)?|divide(?:-[xy])?|fill|stroke|from|via|to|decoration|underline-offset|caret|accent|placeholder|shadow|inset-shadow|drop-shadow|rounded(?:-[a-z]{1,2})?|leading|tracking|font|opacity|z)-(\[[^\]\s"'`]+\]|\(--[\w-]+\)|[\d.]+|px)(?![\w.\-[])/g

/** An arbitrary property that sets what a token utility sets, such as `[padding:13px]`. */
const DESIGN_PROPERTY =
  /(?<![\w-])\[((?:padding|margin|inset|gap|row-gap|column-gap|top|right|bottom|left|width|height|min-width|min-height|max-width|max-height|color|background|border|outline|font|line-height|letter-spacing|box-shadow|text-shadow|opacity|z-index)(?:-[a-z]+)*):([^\]\s"'`]+)\]/g

/** A spacing utility, whose numeric step must be on the scale. */
const SPACING_UTILITY = /^(?:p[xytrblse]?|m[xytrblse]?|gap(?:-[xy])?|space-[xy])$/

/** A token: a variable, with an optional fallback, a step of the spacing scale, or an environment variable. */
const TOKEN_REFERENCE = /var\(--[\w-]+(?:,[^()]*(?:\([^()]*\))?[^()]*)?\)|--spacing\([\d.]+\)|env\([\w-]+\)/g

/** An absolute length or a colour beside a token. A share of the parent or the screen and a 1px hairline pass there. */
const RAW_VALUE =
  /(?<![A-Za-z0-9.])(?!1px(?![A-Za-z0-9]))\d*\.?\d+(?:px|rem|em|ch|lh|ex|pt)(?![A-Za-z0-9])|#[0-9a-fA-F]{3,8}(?![A-Za-z0-9])|\b(?:rgba?|hsla?|hwb|lab|lch|oklab|oklch|color-mix)\(/

/** A bracketed value names no token, or a raw length or colour beside one. */
function isRaw(value: string): boolean {
  const rest = value.replace(TOKEN_REFERENCE, "")
  return rest === value || RAW_VALUE.test(rest)
}

/** A `--spacing(n)` in a value whose step is not on the scale. */
function hasStepOffTheScale(value: string): boolean {
  return [...value.matchAll(/--spacing\(([\d.]+)\)/g)].some((match) => !SPACING_STEPS.has(match[1] ?? ""))
}

function isOffTheScale(utility: string, value: string): boolean {
  if (value.startsWith("(--")) return false
  const isSpacing = SPACING_UTILITY.test(utility)
  if (value.startsWith("[")) return isRaw(value.slice(1, -1)) || (isSpacing && hasStepOffTheScale(value))
  return isSpacing && !SPACING_STEPS.has(value)
}

/** Every class in a text that names a value outside the tokens. */
function findOffTheScale(text: string): string[] {
  const utilities = [...text.matchAll(TOKEN_UTILITY)].filter((match) => isOffTheScale(match[1] ?? "", match[2] ?? ""))
  const properties = [...text.matchAll(DESIGN_PROPERTY)].filter((match) => isRaw(match[2] ?? ""))
  return [...utilities, ...properties].map((match) => match[0].replace(/^-/, ""))
}

function listSourceFiles(directory: string): string[] {
  return readdirSync(directory, { recursive: true, encoding: "utf8" })
    .filter((file) => /\.([jt]sx?|css)$/.test(file) && !/\.(test|spec)\./.test(file))
    .map((file) => path.join(directory, file))
}

/** Every class of a file that names a value outside the tokens, as `<file>: <class>`. */
function findOffenders(file: string): string[] {
  const relative = path.relative(SOURCE, file).split(path.sep).join("/")
  if (THEME.has(relative)) return []
  return findOffTheScale(readFileSync(file, "utf8")).map((offTheScale) => `${relative}: ${offTheScale}`)
}

const offenders = listSourceFiles(SOURCE).flatMap(findOffenders)

describe("design tokens", () => {
  it("are the only values a class names", () => {
    assert.deepEqual(
      offenders.filter((offender) => !EXEMPT.has(offender)),
      [],
      "These classes name a value outside the tokens of DESIGN.md. Use a token, or add one to the theme where" +
        " none fits. Only content (a picture, a game's or another app's own look) is exempt.",
    )
  })

  it("exempt only classes that still occur", () => {
    expect([...EXEMPT.keys()].filter((exempt) => !offenders.includes(exempt))).toEqual([])
  })

  /** A wrong folder or a broken pattern would make the rule above pass by finding nothing. */
  it("read the sources and recognise a value off the scale", () => {
    expect(listSourceFiles(SOURCE)).not.toEqual([])
    const offTheScale = [
      "p-[13px]",
      "gap-7",
      "text-[#123456]",
      "max-w-[calc(100%-2rem)]",
      "rounded-[min(var(--radius),12px)]",
      "p-[--spacing(7)]",
      "shadow-[0_4px_12px_var(--shadow)]",
      "[padding:13px]",
    ]
    expect(findOffTheScale(offTheScale.join(" "))).toEqual(offTheScale)
    const onTheScale = [
      "p-4",
      "-mt-2",
      "gap-(--gap)",
      "h-[calc(var(--rows)*var(--cell))]",
      "max-w-[calc(100%-(--spacing(8)))]",
      "after:h-[calc(var(--gap)+1px)]",
      "data-[size=sm]:px-2",
      "[--gap:0.75rem]",
      "pb-[env(safe-area-inset-bottom)]",
    ]
    expect(findOffTheScale(onTheScale.join(" "))).toEqual([])
  })
})
