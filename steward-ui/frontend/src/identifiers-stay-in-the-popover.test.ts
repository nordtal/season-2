import { describe, expect, it } from "vitest"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)))

/**
 * steward/45's last requirement: a test that goes red when an identifier is drawn anywhere else.
 *
 * The ticket's own reason for wanting it: without it the rule holds exactly until the next page,
 * and with it the rule is a promise. `pages/access.test.tsx` already renders the access table and
 * asserts no raw id appears in it - that is the same rule for one page, proven the better way,
 * by rendering. This file is the other half: it cannot render every page, so it reads the source
 * instead and asks a narrower question that needs no fixtures and no mocks.
 *
 * **The question is: does a Discord id or a Minecraft uuid get written into the page?** Not
 * "is the field mentioned" - filtering a search over `person.discordId`, keying a row by it, or
 * passing it to `PersonIdentity` are all correct and common. What is forbidden is putting the
 * value where a human reads it: as JSX text, or as a `title` tooltip, which is a page the browser
 * draws on hover and no less visible for being late.
 *
 * `identity.tsx` is exempt because it IS the popover - the one place the ticket puts them, behind
 * a click, next to a copy button.
 */
const IDENTIFIER_FIELDS = ["discordId", "minecraftUuid", "mcUuid"]

/** The component that is allowed to draw them, and nothing else. */
const ALLOWED = ["components/steward/identity.tsx"]

/**
 * Where an expression ends up in front of a person: as a JSX child, or as a `title` tooltip - a
 * page the browser draws on hover, no less read for being late.
 *
 * A JSX child is recognised by what precedes the brace: `>` or nothing but indentation. That is
 * what separates it from the two shapes that mention the same field for good reasons - an object
 * literal (`{ discordId: id.trim() }`, a request body) and an `import`/`export` list.
 *
 * <h2>What this cannot see, stated rather than hidden</h2>
 * It reads one line at a time, so a JSX child whose expression is wrapped over several lines is
 * invisible to it - `{person.minecraftUuid ?? (` opens a brace this pattern never closes. That is
 * a real hole and the reason `pages/access.test.tsx` exists: rendering the page is the better
 * proof and this file does not replace it. What this one buys is the pages that have no render
 * test at all, and the day somebody adds a table to one of them.
 */
const JSX_CHILD = /(?:>|^\s*)\{([^{}:]*)\}/
const TOOLTIP = /title=\{([^{}]*)\}/

function sourceFiles(directory: string): string[] {
  const found: string[] = []
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const full = path.join(directory, entry.name)
    if (entry.isDirectory()) {
      found.push(...sourceFiles(full))
    } else if (entry.name.endsWith(".tsx") && !entry.name.includes(".test.")) {
      found.push(full)
    }
  }
  return found
}

/** Every place an identifier field ends up as something a person reads. */
function drawnIdentifiers(): string[] {
  const offenders: string[] = []
  for (const file of sourceFiles(source)) {
    const relative = path.relative(source, file)
    if (ALLOWED.includes(relative)) continue
    const text = fs.readFileSync(file, "utf8")
    const lines = text.split("\n")
    for (const [index, line] of lines.entries()) {
      for (const pattern of [JSX_CHILD, TOOLTIP]) {
        const match = pattern.exec(line)
        if (!match) continue
        const expression = match[1] ?? ""
        if (IDENTIFIER_FIELDS.some((field) => expression.includes(field))) {
          offenders.push(`${relative}:${index + 1}  ${line.trim().slice(0, 90)}`)
          break
        }
      }
    }
  }
  return offenders
}

describe("an identifier is drawn in one place and nowhere else (steward/45)", () => {
  it("no page writes a Discord id or a Minecraft uuid where somebody reads it", () => {
    const offenders = drawnIdentifiers()
    expect(
      offenders,
      "These draw a raw identifier. steward/45 put them in PersonIdentity's popover, behind a\n" +
        "click and next to a copy button, because a table full of 19-digit numbers is a table\n" +
        "nobody reads. If a new one is genuinely right, it belongs in identity.tsx.\n\n" +
        offenders.join("\n"),
    ).toEqual([])
  })

  it("actually reads the sources, so an empty result means something", () => {
    // Without this, a broken path or a changed extension makes the rule above pass by finding
    // nothing at all - the failure mode that leaves a green build and a guard nobody can trust.
    const files = sourceFiles(source)
    expect(files.length).toBeGreaterThan(30)
    expect(files.some((f) => f.endsWith("pages/access.tsx"))).toBe(true)
  })
})
