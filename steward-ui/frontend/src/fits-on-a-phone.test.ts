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
 */
describe("the rules that make it fit on a phone", () => {
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

  it("lets an image reference break inside a table card", () => {
    const css = fs.readFileSync(path.join(source, "index.css"), "utf8")
    const mobile = css.slice(css.indexOf("@media (width < 48rem)")).replace(/\/\*[\s\S]*?\*\//g, "")
    expect(
      mobile,
      "`ghcr.io/nordtal/steward-deployer:latest` is one word to a line breaker and 281px wide in a" +
        " 200px column. Without overflow-wrap it hangs off the right edge of the phone.",
    ).toContain("overflow-wrap: anywhere")
  })
})
