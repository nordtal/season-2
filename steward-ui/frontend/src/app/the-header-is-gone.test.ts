import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { assert, describe, expect, it } from "vitest"

/**
 * A guard that does not let the header come back.
 *
 * The top bar goes, in both states, expanded sidebar included. That is easy to obey once and easy
 * to undo by accident - a header is the first thing anybody adds when a page needs a title, a
 * button or a filter row, and the second one would be back within a week without anything
 * noticing. jsdom cannot see it: the shell needs a router, and a bar drawn above the content has
 * the same box as no bar at all where there is no layout. So this reads the sources.
 *
 * Two more things ride along, because they are the same decision and have the same failure mode:
 * the sidebar must really collapse, and the search must not be lost with the bar that used to
 * carry it - on a phone there is no `⌘K`, so a shell with nowhere to tap has no command palette.
 */
const app = path.dirname(fileURLToPath(import.meta.url))

/** Every shell source, comments blanked - a comment about a header is not a header. */
function shellSources(): Array<{ file: string; text: string }> {
  return readdirSync(app)
    .filter((name) => name.endsWith(".tsx") && !name.includes(".test."))
    .map((name) => ({
      file: name,
      text: readFileSync(path.join(app, name), "utf8")
        .replace(/\/\*[\s\S]*?\*\//g, (comment) => comment.replace(/[^\n]/g, " "))
        .replace(/^\s*\/\/.*$/gm, " "),
    }))
}

describe("the header is gone and does not grow back", () => {
  it("reads the sources, so an empty result means something", () => {
    const sources = shellSources()
    expect(sources.length).toBeGreaterThan(5)
    expect(sources.some(({ file }) => file === "shell.tsx")).toBe(true)
    expect(sources.some(({ file }) => file === "frames.tsx")).toBe(true)
  })

  it("has no header element anywhere in the shell", () => {
    const offenders = shellSources()
      .filter(({ text }) => /<header[\s>]/.test(text))
      .map(({ file }) => file)

    assert.deepEqual(
      offenders,
      [],
      "The top bar was removed, and its border with it. What it carried is the island at the top" +
        " left and the account picture level with it - a new `header` here is the old bar coming" +
        " back under another name.",
    )
  })

  it("lets the sidebar collapse rather than holding it open", () => {
    const shell = shellSources().find(({ file }) => file === "shell.tsx")!.text
    const provider = /<SidebarProvider([\s\S]*?)>/.exec(shell)?.[1] ?? ""

    assert.notEqual(provider, "", "The shell must mount a SidebarProvider.")
    assert.isFalse(/^\s*open\s*$/m.test(provider), "A bare `open` prop pins the sidebar open forever.")
    assert.include(
      provider,
      "defaultOpen",
      "Without `defaultOpen` read from the cookie the provider writes, a collapsed sidebar" +
        " springs open again on the next reload.",
    )
  })

  it("keeps a way to the command palette, which the bar used to carry", () => {
    const frames = shellSources().find(({ file }) => file === "frames.tsx")!.text
    const frame = frames.split("export function AppFrame")[1] ?? ""

    assert.notEqual(frame, "", "The frame is `AppFrame` in `frames.tsx`.")
    assert.isTrue(
      /SearchButton/.test(frame),
      "A phone has no `⌘K`. A frame with nothing to tap has no command palette at all, so the" +
        " search has to be somewhere on it and the comment there has to say where.",
    )
  })

  it("does not grow a second shell back behind a query parameter", () => {
    /**
     * There is exactly one shell frame; a comparison between several shells behind a query
     * parameter is a fork in the code nobody is standing at, and each fork has its own answer to
     * where the search goes.
     */
    const offenders = shellSources()
      .filter(({ text }) => /export function Shell[A-Z]\b/.test(text))
      .map(({ file }) => file)

    assert.deepEqual(offenders, [], "The shell comparison is over; there is one frame.")
  })
})
