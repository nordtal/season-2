import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { describe, expect, it } from "vitest"

/**
 * steward/89's own checkbox: a guard that does not let the header come back.
 *
 * Till, 2026-09-17: the top bar goes, in both states, expanded sidebar included. That is easy to
 * obey once and easy to undo by accident - a header is the first thing anybody adds when a page
 * needs a title, a button or a filter row, and the second one would be back within a week without
 * anything noticing. jsdom cannot see it: the shell needs a router, and a bar drawn above the
 * content has the same box as no bar at all where there is no layout. So this reads the sources.
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

describe("the header is gone and does not grow back (steward/89)", () => {
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

    expect(
      offenders,
      "The top bar was removed on Till's order (steward/89) and its border with it. What it" +
        " carried is the island at the top left and the account picture level with it - a new" +
        " `header` here is the old bar coming back under another name.",
    ).toEqual([])
  })

  it("lets the sidebar collapse rather than holding it open", () => {
    const shell = shellSources().find(({ file }) => file === "shell.tsx")!.text
    const provider = /<SidebarProvider([\s\S]*?)>/.exec(shell)?.[1] ?? ""

    expect(provider, "The shell must mount a SidebarProvider.").not.toEqual("")
    expect(
      /^\s*open\s*$/m.test(provider),
      "A bare `open` prop pins the sidebar open forever, which is what steward/89 undid.",
    ).toBe(false)
    expect(
      provider,
      "Without `defaultOpen` read from the cookie the provider writes, a collapsed sidebar" +
        " springs open again on the next reload.",
    ).toContain("defaultOpen")
  })

  it("keeps a way to the command palette, which the bar used to carry", () => {
    const frames = shellSources().find(({ file }) => file === "frames.tsx")!.text
    const frame = frames.split("export function AppFrame")[1] ?? ""

    expect(frame, "The frame is `AppFrame` in `frames.tsx` (steward/89).").not.toEqual("")
    expect(
      /SearchButton/.test(frame),
      "A phone has no `⌘K`. A frame with nothing to tap has no command palette at all, so the" +
        " search has to be somewhere on it and the comment there has to say where.",
    ).toBe(true)
  })

  it("does not grow a second shell back behind a query parameter", () => {
    // Nine shells stood side by side while steward/89 was a question (`?shell=a` … `?shell=i`).
    // Till answered it on 2026-09-17, and the eight he did not pick were deleted rather than kept
    // - a comparison nobody is standing at is a fork in the code, and each of those shells had its
    // own answer to where the search goes.
    const offenders = shellSources()
      .filter(({ text }) => /export function Shell[A-Z]\b/.test(text))
      .map(({ file }) => file)

    expect(offenders, "The shell comparison is over; there is one frame.").toEqual([])
  })
})
