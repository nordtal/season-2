import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { assert, describe, expect, it } from "vitest"

const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)))

/**
 * Nothing in the app reaches for a raw dialog: every one goes through `responsive-dialog.tsx`.
 *
 * Every dialog in the whole app is to be a dialog or a bottom
 * sheet depending on the width, decided by one component - and `Dialog` is not to be used raw
 * anywhere, the command palette and the security-key prompt included. Writing the exceptions into a
 * comment alone is exactly the kind
 * of note the next dialog is written without having read.
 *
 * So the rule is a test. A page that imports `Dialog`, `AlertDialog`, `Sheet` or `Drawer` from
 * `components/ui/` fails here, and the failure names the file. The primitives stay where they are:
 * they are what the responsive component is built out of, and this rule is about who may reach past
 * it.
 *
 * <h2>Why the import and not the element</h2>
 * A JSX tag can be renamed at the import (`Dialog as Shell`) and a regex over `<Dialog` would miss
 * it, while a regex over the identifier would flag every mention in a comment. The import line is
 * the one thing a call site cannot do without.
 */
const RAW = /from\s+"@\/components\/ui\/(dialog|alert-dialog|sheet|drawer)"/

/**
 * The files allowed to import a raw one, each with the reason in its own words.
 *
 * Exemption is by file, the same shape `no-middle-dot.test.ts` and
 * `identifiers-stay-in-the-popover.test.ts` both use: a file half-converted looks exactly like one
 * this test never read.
 */
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
    /**
     * Named here rather than trusting the sweep above: both are shells rather than pages, so a
     * revert would read like a refactor.
     */
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
