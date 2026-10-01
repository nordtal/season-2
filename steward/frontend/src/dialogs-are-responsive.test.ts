import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { assert, describe, expect, it } from "vitest"

const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)))

/** Every dialog goes through `responsive-dialog.tsx`; the import is checked, since a JSX tag can be renamed there. */
const RAW = /from\s+"@\/components\/ui\/(dialog|alert-dialog|sheet|drawer)"/

/** The files allowed to import a raw one, each with its reason; exemption is by file. */
const ALLOWED = new Map<string, string>([
  [
    path.join("components", "ui", "responsive-dialog.tsx"),
    "the component itself - it is the one place the choice between the two shapes is made",
  ],
  [
    path.join("components", "ui", "sidebar.tsx"),
    "the navigation on a phone, which is a side sheet and not a dialog: it comes from the edge, it" +
      " is the app's own chrome rather than something a page opened, and a bottom sheet would" +
      " land on top of the thing it is navigating away from",
  ],
])

function sourceFiles(directory: string): string[] {
  const found: string[] = []
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const full = path.join(directory, entry.name)
    if (entry.isDirectory()) {
      found.push(...sourceFiles(full))
    } else if ((entry.name.endsWith(".tsx") || entry.name.endsWith(".ts")) && !entry.name.includes(".test.")) {
      found.push(full)
    }
  }
  return found
}

/** The primitives themselves, which import nothing of the kind and are not call sites either. */
const PRIMITIVES = new Set(
  ["dialog.tsx", "alert-dialog.tsx", "sheet.tsx", "drawer.tsx"].map((name) => path.join("components", "ui", name)),
)

function offenders(): string[] {
  const found: string[] = []
  for (const file of sourceFiles(source)) {
    const relative = path.relative(source, file)
    if (ALLOWED.has(relative) || PRIMITIVES.has(relative)) continue
    readFileSync(file, "utf8")
      .split("\n")
      .forEach((line, index) => {
        if (RAW.test(line)) {
          found.push(`${relative}:${index + 1}  ${line.trim()}`)
        }
      })
  }
  return found
}

describe("every dialog goes through the responsive one", () => {
  it("leaves the sheet the full width of a phone, so nothing narrows it without an `sm:`", () => {
    /** The drawer is `inset-x-0`; an unprefixed `max-w-*` on it leaves a gap on the right, as the palette once had. */
    const tags =
      /<(ResponsiveDialogContent|ResponsiveAlertDialogContent|CommandDialog)\b((?:[^>"{]|"[^"]*"|\{(?:[^{}]|\{[^{}]*\})*\})*)>/g
    const narrowing = /(^|\s)(max-w-|w-(?!full)|mx-|inset-|left-|right-|-?translate-x-)\S*/
    const found: string[] = []
    for (const file of sourceFiles(source)) {
      for (const [, name, attributes] of readFileSync(file, "utf8").matchAll(tags)) {
        const classes = [...attributes.matchAll(/"([^"\n]*)"/g)].map((m) => m[1]).join(" ")
        const hit = classes.match(narrowing)
        if (hit) found.push(`${path.relative(source, file)}: <${name}> ${hit[0].trim()}`)
      }
    }
    expect(found).toEqual([])
  })

  it("no file outside the component imports a raw dialog, alert dialog, sheet or drawer", () => {
    expect(offenders()).toEqual([])
  })

  it("the exemptions name files that still exist", () => {
    for (const [file, why] of ALLOWED) {
      assert.include(
        sourceFiles(source).map((full) => path.relative(source, full)),
        file,
        `${file} is exempt (${why}) and is no longer there - a stale exemption is a rule that has` +
          ` quietly stopped applying to whatever replaced it`,
      )
    }
  })

  it("the command palette and the security-key prompt are converted, by name", () => {
    /** Named explicitly, since a revert of either shell would read like a refactor. */
    for (const file of [path.join("components", "ui", "command.tsx"), path.join("app", "security-keys.tsx")]) {
      const text = readFileSync(path.join(source, file), "utf8")
      assert.include(
        text,
        'from "@/components/ui/responsive-dialog"',
        `${file} no longer builds on the responsive component`,
      )
    }
  })
})
