import { assert, describe, it } from "vitest"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)))

/**
 * Source rules that keep the interface fitting a 390px screen, since jsdom has no layout to measure.
 *
 * They only prove a rule is still written; a change to a width needs a picture from `ui-shots`.
 */
/**
 * Every double-quoted string in the sources shaped like a Tailwind class list, `cn(…)` and `cva(…)` included.
 *
 * Comments are stripped first, since a comment explaining a grid is not a grid.
 */
function everyClassAttribute(directory: string): Array<{ file: string; classes: string }> {
  const found: Array<{ file: string; classes: string }> = []
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const full = path.join(directory, entry.name)
    if (entry.isDirectory()) {
      found.push(...everyClassAttribute(full))
      continue
    }
    if (!entry.name.endsWith(".tsx") || entry.name.includes(".test.")) continue
    const text = fs
      .readFileSync(full, "utf8")
      .replace(/\/\*[\s\S]*?\*\//g, " ")
      .replace(/^\s*\/\/.*$/gm, " ")
    for (const match of text.matchAll(/"([^"\n]*)"/g)) {
      const classes = match[1]
      if (!/-/.test(classes) || /[.,;?!]\s|[.,;?!]$/.test(classes)) continue
      found.push({ file: path.relative(source, full), classes })
    }
  }
  return found
}

describe("the rules that make it fit on a phone", () => {
  it("gives every grid an explicit column count, so one long word cannot widen the page", () => {
    /** Below `lg` a grid without `grid-cols-1` has an implicit `auto` track, which one snowflake widened past 390px. */
    const offenders = everyClassAttribute(source)
      .filter(({ classes }) => /(^|\s)grid(\s|$)/.test(classes))
      .filter(({ classes }) => !/(^|\s)grid-cols-/.test(classes))
      .filter(({ classes }) => /grid-cols-/.test(classes) || !/grid-(rows|flow)|auto-rows/.test(classes))

    assert.deepEqual(
      offenders,
      [],
      "A grid with no unprefixed `grid-cols-*` has an implicit `auto` column, which is as wide as" +
        " its widest unbreakable content and ignores the screen. Add `grid-cols-1`.",
    )
  })

  it("does not size the shell with a viewport unit, because a home screen gets those wrong", () => {
    /** `h-svh` cut the page and the sidebar off on an iPhone home screen; `--app-height` is measured instead. */
    const shell = fs.readFileSync(path.join(source, "app/shell.tsx"), "utf8")
    const sidebar = fs.readFileSync(path.join(source, "components/ui/sidebar.tsx"), "utf8")
    const classes = [shell, sidebar]
      .flatMap((file) => [...file.matchAll(/class(?:Name)?="([^"]*)"/g)].map((m) => m[1]))
      .concat([...sidebar.matchAll(/"([^"]*(?:svh|dvh|vh)[^"]*)"/g)].map((m) => m[1]))
      .join(" ")

    assert.notMatch(
      classes,
      /\b(?:min-)?h-(?:svh|dvh|screen)\b/,
      "The shell and the sidebar are sized from `--app-height`, which is measured. A viewport unit" +
        " here is the bug of 2026-09-14 coming back: on an iOS home screen it is not the window.",
    )
  })

  it("caps the scroll area's inner box at the viewport, rather than at its content", () => {
    const scrollArea = fs.readFileSync(path.join(source, "components/ui/scroll-area.tsx"), "utf8")
    /** Class attributes only, since the comment beside the rule names it too. */
    const classes = [...scrollArea.matchAll(/className="([^"]*)"/g)].map((m) => m[1]).join(" ")
    assert.include(
      classes,
      "[&>div]:!block",
      "Radix gives the viewport's only child an inline `display: table`, and a table box is as wide" +
        " as its widest line. Without `[&>div]:!block` every page is as wide as its longest log" +
        " line, the scroll area clips at the screen's width and renders no horizontal bar, and the" +
        " right-hand third of the interface is drawn where no finger can reach it.",
    )
  })

  it("caps a status badge at the width of the cell it sits in", () => {
    /** `Badge` is `w-fit shrink-0 overflow-hidden`, so without the cap a date is clipped with no ellipsis. */
    const status = fs.readFileSync(path.join(source, "components/steward/status.tsx"), "utf8")
    const classes = [...status.matchAll(/"([^"\n]*)"/g)].map((m) => m[1]).join(" ")

    assert.match(
      classes,
      /max-w-full/,
      "StatusBadge must cap itself at `max-w-full` and end in an ellipsis rather than a hard cut." +
        " Badge is `w-fit shrink-0 whitespace-nowrap overflow-hidden`, so without this it is drawn" +
        " at its full text width and the table container clips it silently.",
    )
    assert.match(classes, /truncate/, "…and `truncate`, so what does not fit ends in an ellipsis.")
  })

  it("lets an image reference break inside a table card", () => {
    const css = fs.readFileSync(path.join(source, "index.css"), "utf8")
    const mobile = css.slice(css.indexOf("@media (width < 48rem)")).replace(/\/\*[\s\S]*?\*\//g, "")
    assert.include(
      mobile,
      "overflow-wrap: anywhere",
      "`ghcr.io/nordtal/steward-deployer:latest` is one word to a line breaker and 281px wide in a" +
        " 200px column. Without overflow-wrap it hangs off the right edge of the phone.",
    )
  })

  it("lets a stacked cell actually wrap, not just break inside an unbreakable word", () => {
    /** `whitespace-nowrap` on the cells outranks the stacked rule, so `overflow-wrap` alone never wraps prose. */
    const css = fs.readFileSync(path.join(source, "index.css"), "utf8")
    const mobile = css.slice(css.indexOf("@media (width < 48rem)")).replace(/\/\*[\s\S]*?\*\//g, "")
    assert.match(
      mobile,
      /white-space:\s*normal/,
      "`.steward-table :where(th, td)` must also set `white-space: normal` in the stacked layout -" +
        " `overflow-wrap: anywhere` alone cannot wrap ordinary text while `whitespace-nowrap` (from" +
        " TableCell/TableHead) still applies.",
    )
  })

  it("puts a cell's second child beside its label instead of underneath it", () => {
    /** A labelled cell's second child would auto-place into the label column and collide with the next label. */
    const css = fs.readFileSync(path.join(source, "index.css"), "utf8")
    const mobile = css.slice(css.indexOf("@media (width < 48rem)")).replace(/\/\*[\s\S]*?\*\//g, "")
    assert.match(
      mobile,
      /td\[data-label\]\s*>\s*\*\s*\{[^}]*grid-column:\s*2/,
      "`.steward-table td[data-label] > *` must set `grid-column: 2`, so every child of a labelled" +
        " cell sits beside the label rather than the first under it and the rest defaulting into" +
        " the label's own column.",
    )
  })
})
