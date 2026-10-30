import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { assert, describe, expect, it } from "vitest"

const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)))

/**
 * Components whose `title` is a heading or a name they draw themselves, each with its reason. Any other tag that
 * takes `title`, an element or a component that hands it on to one, would show the browser's tooltip.
 */
const OWN_TITLE = new Map<string, string>([
  ["AskThenAct", "the dialog's heading"],
  ["CommandDialog", "the dialog's hidden heading"],
  ["CountDialog", "the dialog's heading"],
  ["Empty", "the empty state's line"],
  ["PageHeader", "the page's heading"],
  ["Panel", "the panel's heading"],
  ["RunPage", "the page's heading"],
])

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

/** The tag of the JSX element whose attribute starts at `at`: the nearest `<Name` outside any `{…}`. */
function tagAt(text: string, at: number): string {
  let depth = 0
  for (let index = at - 1; index >= 0; index--) {
    const character = text[index]
    if (character === "}") depth++
    else if (character === "{") depth--
    else if (depth === 0 && character === "<") return /^<([A-Za-z][\w.]*)/.exec(text.slice(index))?.[1] ?? "?"
  }
  return "?"
}

/** Every `title=` on a tag that is not in OWN_TITLE, as `<file>:<line>  <tag>`. */
function offenders(): string[] {
  const found: string[] = []
  for (const file of sourceFiles(source)) {
    /** Blanked, not deleted, so the offsets and line numbers stay true. */
    const text = readFileSync(file, "utf8")
      .replace(/\/\*[\s\S]*?\*\//g, (comment) => comment.replace(/[^\n]/g, " "))
      .replace(/^\s*\/\/.*$/gm, (comment) => comment.replace(/./g, " "))
    for (const match of text.matchAll(/(?<![\w.-])title=[{"']/g)) {
      const tag = tagAt(text, match.index)
      if (OWN_TITLE.has(tag)) continue
      const line = text.slice(0, match.index).split("\n").length
      found.push(`${path.relative(source, file)}:${line}  <${tag}>`)
    }
  }
  return found
}

describe("every tooltip is Steward's own", () => {
  it("sets no title on an element", () => {
    const found = offenders()
    assert.deepEqual(
      found,
      [],
      "A `title` shows the browser's own tooltip, which looks like no other part of Steward and never opens on a" +
        " touch screen. Put the text into a Tooltip (`Hint` on a control, `Tip` on a mark or a disabled control)," +
        " and give an icon-only control its name with `aria-label`. A component whose `title` is a heading it" +
        " draws goes into OWN_TITLE.\n\n" +
        found.join("\n"),
    )
  })

  /** A broken path or a broken scan would make the rule above pass by finding nothing. */
  it("actually reads the sources and finds the titles that are headings", () => {
    const files = sourceFiles(source)
    expect(files.length).toBeGreaterThan(30)
    const tags = new Set<string>()
    for (const file of files) {
      const text = readFileSync(file, "utf8")
      for (const match of text.matchAll(/(?<![\w.-])title=[{"']/g)) tags.add(tagAt(text, match.index))
    }
    expect([...OWN_TITLE.keys()].filter((tag) => !tags.has(tag))).toEqual([])
  })

  it("names the tag of an attribute, past the braces of the attributes before it", () => {
    const text = `<Button onClick={() => { go(<b />) }} size="sm" title="x">`
    expect(tagAt(text, text.indexOf("title="))).toBe("Button")
  })
})
