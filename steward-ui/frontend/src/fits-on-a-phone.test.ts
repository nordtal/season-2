import { describe, expect, it } from "vitest"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)))

/**
 * Two rules that make the interface fit a 390px screen, held here because jsdom cannot check them.
 *
 * Both were found by measuring a real browser at 390 x 844, and neither has a symptom a rendering
 * test could see: jsdom has no layout, so an element drawn 80px off the right edge of the phone has
 * exactly the same box in jsdom as one that fits. What a test *can* do is notice that the rule was
 * deleted - which is the failure mode worth guarding, because both look like tidy-up candidates:
 * one is an `!important` in a vendored shadcn component, and the other is a property nobody needs
 * on a desktop.
 *
 * The measurement itself lives in `/home/dev/ui-shots/tool/overflow.mjs` on the dev server: it
 * walks every box on every page at 390px and prints the ones that stick out. Run that after a
 * layout change; this file only keeps the two known answers from being lost.
 *
 * **THIS FILE CANNOT TELL YOU THAT THE INTERFACE FITS. It can only tell you that a rule which once
 * made it fit has not been deleted.** Written out on 2026-09-17 because steward/103 found four
 * boxes running off the right edge of `/access` and every test here was green while they did -
 * correctly, because a width is a number jsdom does not have and no assertion over source text can
 * produce. The only instrument that sees a width is a picture: `/home/dev/ui-shots/tool/preview.mjs
 * --target=/access --width=390 --out=…` renders the built frontend against the fixtures in
 * `/home/dev/ui-shots/fixtures` with no server and no session, and `overflow.mjs` measures the same
 * thing in numbers when a deployment is running. A guard that is trusted further than it can see is
 * worse than no guard, so: **if the change is about how wide something is, take the picture.**
 */
/**
 * Every string in the sources that is a list of Tailwind classes - wherever it is written.
 *
 * It read `className="…"` only, until 2026-09-14, and that is half of them: the vendored shadcn
 * components write theirs inside `cn(…)` and `cva(…)`, where a `className=` matcher sees nothing.
 * Four grids with no column count were sitting in `chart.tsx`, `alert.tsx`, `dialog.tsx` and
 * `form.tsx` while the rule below reported zero offenders - which is the worst thing a guard can
 * do, because the build stays green and somebody has read the test and believed it.
 *
 * So every double-quoted string is taken, and what makes one a class list is its shape: no
 * sentence punctuation, and at least one hyphenated utility in it. Comments are stripped first,
 * because a comment explaining a grid is not a grid.
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
    // MEASURED 2026-09-14 at 390px. `grid gap-4 lg:grid-cols-2` on the status page drew a card
    // 413px wide on a 390px screen. Below `lg` there is no `grid-template-columns` at all, so the
    // column is an *implicit* track, and an implicit track is `auto` - sized by the min-content of
    // what is in it, with no upper bound from the container. One journal row holding a Discord
    // snowflake was enough; `truncate` does not help, because the automatic minimum is what is
    // being summed. `grid-cols-1` makes the track `minmax(0, 1fr)`, which cannot exceed the
    // container, and everything inside truncates as it was written to.
    //
    // Why a source rule and not a measurement: the measurement is `/home/dev/ui-shots/tool/
    // overflow.mjs` and it needs a running deployment. This one notices the next grid written
    // without a base, which is the moment the cost is a character.
    const offenders = everyClassAttribute(source)
      .filter(({ classes }) => /(^|\s)grid(\s|$)/.test(classes))
      .filter(({ classes }) => !/(^|\s)grid-cols-/.test(classes))
      .filter(({ classes }) => /grid-cols-/.test(classes) || !/grid-(rows|flow)|auto-rows/.test(classes))

    expect(
      offenders,
      "A grid with no unprefixed `grid-cols-*` has an implicit `auto` column, which is as wide as" +
        " its widest unbreakable content and ignores the screen. Add `grid-cols-1`.",
    ).toEqual([])
  })

  it("does not size the shell with a viewport unit, because a home screen gets those wrong", () => {
    // MEASURED 2026-09-14 on an iPhone home screen: at `h-svh` the page and the sidebar sheet were
    // both cut off about a fifth above the bottom edge. `--app-height` is measured from the window
    // by `lib/app-frame.ts` and falls back to `100svh` in the stylesheet, where it is right.
    const shell = fs.readFileSync(path.join(source, "app/shell.tsx"), "utf8")
    const sidebar = fs.readFileSync(path.join(source, "components/ui/sidebar.tsx"), "utf8")
    const classes = [shell, sidebar]
      .flatMap((file) => [...file.matchAll(/class(?:Name)?="([^"]*)"/g)].map((m) => m[1]))
      .concat([...sidebar.matchAll(/"([^"]*(?:svh|dvh|vh)[^"]*)"/g)].map((m) => m[1]))
      .join(" ")

    expect(
      classes,
      "The shell and the sidebar are sized from `--app-height`, which is measured. A viewport unit" +
        " here is the bug of 2026-09-14 coming back: on an iOS home screen it is not the window.",
    ).not.toMatch(/\b(?:min-)?h-(?:svh|dvh|screen)\b/)
  })

  it("caps the scroll area's inner box at the viewport, rather than at its content", () => {
    const scrollArea = fs.readFileSync(path.join(source, "components/ui/scroll-area.tsx"), "utf8")
    // The class attributes only. The comment beside this one names the rule too, and a test that
    // reads the whole file passes on a file where the rule has been deleted and explained.
    const classes = [...scrollArea.matchAll(/className="([^"]*)"/g)].map((m) => m[1]).join(" ")
    expect(
      classes,
      "Radix gives the viewport's only child an inline `display: table`, and a table box is as wide" +
        " as its widest line. Without `[&>div]:!block` every page is as wide as its longest log" +
        " line, the scroll area clips at the screen's width and renders no horizontal bar, and the" +
        " right-hand third of the interface is drawn where no finger can reach it.",
    ).toContain("[&>div]:!block")
  })

  it("caps a status badge at the width of the cell it sits in", () => {
    // MEASURED 2026-09-17 at 390px, steward/103. shadcn's `Badge` is `w-fit shrink-0
    // whitespace-nowrap overflow-hidden`: as wide as its text, refusing to shrink, refusing to
    // wrap, and clipping the rest WITHOUT an ellipsis. In a table card that is a date drawn as
    // `active until 1 Dec 2026, 00:0` - the last digit simply gone, and nothing on screen saying
    // so. `index.css`'s `.steward-table td > *` rule does not reach it, because the badge sits one
    // level deeper inside the tooltip's own span.
    //
    // This is the one half of that finding a source rule can hold: not "the badge fits", which
    // needs a picture, but "the cap somebody measured is still written down". It is the same shape
    // as the scroll-area rule above and it fails for the same reason - somebody tidying away a
    // class that looks redundant on a desktop.
    const status = fs.readFileSync(path.join(source, "components/steward/status.tsx"), "utf8")
    const classes = [...status.matchAll(/"([^"\n]*)"/g)].map((m) => m[1]).join(" ")

    expect(
      classes,
      "StatusBadge must cap itself at `max-w-full` and end in an ellipsis rather than a hard cut." +
        " Badge is `w-fit shrink-0 whitespace-nowrap overflow-hidden`, so without this it is drawn" +
        " at its full text width and the table container clips it silently.",
    ).toMatch(/max-w-full/)
    expect(classes, "…and `truncate`, so what does not fit ends in an ellipsis.").toMatch(
      /truncate/,
    )
  })

  it("lets an image reference break inside a table card", () => {
    const css = fs.readFileSync(path.join(source, "index.css"), "utf8")
    const mobile = css.slice(css.indexOf("@media (width < 48rem)")).replace(/\/\*[\s\S]*?\*\//g, "")
    expect(
      mobile,
      "`ghcr.io/nordtal/steward-deployer:latest` is one word to a line breaker and 281px wide in a" +
        " 200px column. Without overflow-wrap it hangs off the right edge of the phone.",
    ).toContain("overflow-wrap: anywhere")
  })

  it("lets a stacked cell actually wrap, not just break inside an unbreakable word", () => {
    // steward/114, measured at 390px: `TableCell`/`TableHead` carry `whitespace-nowrap` (shadcn's
    // default), and `.steward-table :where(th, td)` in this media block only ever set
    // `overflow-wrap: anywhere`. `overflow-wrap` decides where a line breaks WITHIN an unbreakable
    // run; it cannot make ordinary prose wrap at all while `white-space: nowrap` still holds - that
    // property is more specific here (`.steward-table` + `:where()` beats the utility class), so it
    // wins and the rule never fires. A `detail` sentence like "the Minecraft account was" is not
    // one unbreakable word, so it ran straight off the card with no ellipsis - not the bug
    // steward/103 already fixed, a second one in the same screenshot.
    const css = fs.readFileSync(path.join(source, "index.css"), "utf8")
    const mobile = css.slice(css.indexOf("@media (width < 48rem)")).replace(/\/\*[\s\S]*?\*\//g, "")
    expect(
      mobile,
      "`.steward-table :where(th, td)` must also set `white-space: normal` in the stacked layout -" +
        " `overflow-wrap: anywhere` alone cannot wrap ordinary text while `whitespace-nowrap` (from" +
        " TableCell/TableHead) still applies.",
    ).toMatch(/white-space:\s*normal/)
  })

  it("puts a cell's second child beside its label instead of underneath it", () => {
    // steward/114, measured at 390px: `td[data-label]` is a two-column grid,
    // `minmax(5rem,7rem) minmax(0,1fr)`. A cell with two children - the Journal table's
    // `Concerns`, text plus a `PersonIdentity` line below it - only ever declares the FIRST child's
    // column implicitly; grid auto-placement puts the second one in row 2, column 1, which is the
    // label column, and it collides with the next cell's `::before` label. Measured: the identity
    // line sat at `left 85 … right 169`, exactly the label column's box.
    const css = fs.readFileSync(path.join(source, "index.css"), "utf8")
    const mobile = css.slice(css.indexOf("@media (width < 48rem)")).replace(/\/\*[\s\S]*?\*\//g, "")
    expect(
      mobile,
      "`.steward-table td[data-label] > *` must set `grid-column: 2`, so every child of a labelled" +
        " cell sits beside the label rather than the first under it and the rest defaulting into" +
        " the label's own column.",
    ).toMatch(/td\[data-label\]\s*>\s*\*\s*\{[^}]*grid-column:\s*2/)
  })
})
