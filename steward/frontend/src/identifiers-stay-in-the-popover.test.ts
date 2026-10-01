import { assert, describe, expect, it } from "vitest"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)))

/**
 * Goes red when a Discord id or Minecraft uuid is drawn outside the identity popover.
 *
 * Reads the source, so it covers pages with no render test; `pages/access.test.tsx` proves the rule by rendering.
 */
const IDENTIFIER_FIELDS = ["discordId", "minecraftUuid", "mcUuid", "actorId", "requestedBy"]

const ALLOWED = ["components/steward/identity.tsx"]

/** Only `Entity` decides whether an identifier is a person, so only it may import `PersonIdentity`. */
const MAY_IMPORT_PERSON_IDENTITY = ["components/steward/entity.tsx"]
const PERSON_IDENTITY_IMPORT = /import\s*\{[^}]*\bPersonIdentity\b[^}]*\}\s*from\s*"@\/components\/steward\/identity"/

/**
 * An expression a person reads: a JSX child, preceded by `>` or indentation, or a `title` tooltip.
 *
 * One line at a time, so a JSX child wrapped over several lines is invisible to it.
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

describe("an identifier is drawn in one place and nowhere else", () => {
  it("no page writes a Discord id or a Minecraft uuid where somebody reads it", () => {
    const offenders = drawnIdentifiers()
    assert.deepEqual(
      offenders,
      [],
      "These draw a raw identifier. Identifiers belong in PersonIdentity's popover, behind a\n" +
        "click and next to a copy button, because a table full of 19-digit numbers is a table\n" +
        "nobody reads. If a new one is genuinely right, it belongs in identity.tsx.\n\n" +
        offenders.join("\n"),
    )
  })

  it("no page draws a person except through Entity", () => {
    const offenders = sourceFiles(source)
      .map((file) => path.relative(source, file))
      .filter((relative) => !MAY_IMPORT_PERSON_IDENTITY.includes(relative))
      .filter((relative) => PERSON_IDENTITY_IMPORT.test(fs.readFileSync(path.join(source, relative), "utf8")))
    assert.deepEqual(
      offenders,
      [],
      "These import PersonIdentity directly. Draw an identifier with <Entity id=... />, which\n" +
        "works out by itself whether it is a person, a service or unknown.",
    )
  })

  it("actually reads the sources, so an empty result means something", () => {
    /** Without this, a broken path or extension makes the rule above pass by finding nothing. */
    const files = sourceFiles(source)
    expect(files.length).toBeGreaterThan(30)
    expect(files.some((f) => f.endsWith("pages/access.tsx"))).toBe(true)
  })
})
