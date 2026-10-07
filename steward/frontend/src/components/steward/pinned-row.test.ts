import { readFileSync, readdirSync, statSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { assert, describe, expect, it } from "vitest"

const src = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..")

/** Every non-test source under `src`, since jsdom has no layout to show where a sticky row lands. */
function sources(dir: string = src): Array<{ file: string; text: string }> {
  return readdirSync(dir).flatMap((name) => {
    const full = path.join(dir, name)
    if (statSync(full).isDirectory()) return sources(full)
    if (!/\.tsx?$/.test(name) || name.includes(".test.")) return []
    return [{ file: path.relative(src, full), text: readFileSync(full, "utf8") }]
  })
}

/** Every class list that pins something to the bottom of the scrolling content. */
function pinnedToTheBottom(): Array<{ file: string; classes: string }> {
  return sources().flatMap(({ file, text }) =>
    [...text.matchAll(/["`]([^"`]*\bsticky\b[^"`]*)["`]/g)]
      .map((match) => match[1])
      .filter((classes) => /(^|\s)bottom-/.test(classes))
      .map((classes) => ({ file, classes })),
  )
}

describe("a row pinned to the bottom of the content", () => {
  it("is found in the sources, so an empty answer means something", () => {
    expect(pinnedToTheBottom().length).toBeGreaterThan(0)
  })

  it("sticks at the content's bottom offset, which clears the phone's dock", () => {
    const offenders = pinnedToTheBottom().filter(
      ({ classes }) => !classes.split(/\s+/).includes("bottom-(--content-bottom)"),
    )
    assert.deepEqual(
      offenders,
      [],
      "A sticky row stuck a fixed distance above the scroll area's edge sits under the dock on a phone." +
        " The frame names the room its chrome takes at the bottom as --content-bottom, which the content's" +
        " own padding reads too.",
    )
  })

  it("is drawn by one component only", () => {
    const files = [...new Set(pinnedToTheBottom().map(({ file }) => file))]
    assert.deepEqual(files, ["components/steward/settings-view.tsx"])
    expect(pinnedToTheBottom()).toHaveLength(1)
  })
})

describe("the frame's bottom room", () => {
  it("is one offset, which each shape defines and the content pads by", () => {
    const frames = readFileSync(path.join(src, "app/frames.tsx"), "utf8")
    expect(frames.match(/\[--content-bottom:/g)).toHaveLength(2)
    expect(frames).toContain("pb-(--content-bottom)")
    expect(frames).not.toMatch(/<Content className="[^"]*\bpb-/)
  })
})
