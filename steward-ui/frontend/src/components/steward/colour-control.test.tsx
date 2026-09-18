import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import {
  ColourControl,
  MINECRAFT_CHAT_BACKGROUND,
  SAMPLE_TEXT,
  colourRuns,
} from "@/components/steward/colour-control"
import { colourValue } from "@/components/steward/config-controls"
import type { ConfigEntry } from "@/lib/api"

/**
 * The colour picker steward/63 asks for, and the heuristic (`colourValue`, `config-controls.tsx`)
 * and grouping (`colourRuns`) that decide when it is drawn at all and whether several of them stand
 * in a row.
 *
 * The ticket's own escape hatch is a heuristic on the VALUE, not the key - the interface recognises
 * that a value looks like `#rrggbb` and shows the picker - deliberately instead of a schema
 * "colour" kind (steward/54), which needs a jcore change out of scope tonight. `colourValue`'s own comment in
 * `config-controls.tsx` explains why it reads `entry.value` and not a live keystroke; the tests
 * below are what proves that choice rather than merely asserting it.
 */

function field(over: Partial<ConfigEntry> & { key: string; path: string }): ConfigEntry {
  return {
    label: over.key,
    comments: [],
    explanation: "",
    noExplanationNeeded: false,
    filled: true,
    value: "",
    items: [],
    kind: "SCALAR",
    type: "STRING",
    line: 1,
    editable: true,
    secret: false,
    inSchema: true,
    ...over,
  }
}

afterEach(cleanup)

describe("colourValue", () => {
  it("recognises a stored value shaped like #rrggbb", () => {
    expect(colourValue(field({ key: "good", path: "good", value: "#8ba888" }))).toBe("#8ba888")
    // Case-insensitive - jcore writes lower-case, but a hand-edited file is not required to.
    expect(colourValue(field({ key: "good", path: "good", value: "#8BA888" }))).toBe("#8BA888")
  })

  it("never mistakes an empty value for a colour", () => {
    // RED, first: before this guard the naive regex `#[0-9a-f]{6}$` test against "" is simply
    // false already, so this looks like it would pass by accident - the case that actually needs
    // the explicit early return is a value of undefined, which `entry.value` is for a kind this
    // heuristic must never touch (see the next test). Both are asserted here so a later refactor
    // that inlines the regex cannot quietly drop either guard.
    expect(colourValue(field({ key: "good", path: "good", value: "" }))).toBeNull()
  })

  it("rejects an ordinary text value, including one that starts with #", () => {
    expect(colourValue(field({ key: "note", path: "note", value: "just text" }))).toBeNull()
    // The named failure mode from the code comment: a value that merely starts with a hash and six
    // hex-looking characters is not enough more than that to be worth a false positive over - but
    // "#1234567" (seven digits) and "#12345" (five) both still have to be rejected.
    expect(colourValue(field({ key: "note", path: "note", value: "#1234567" }))).toBeNull()
    expect(colourValue(field({ key: "note", path: "note", value: "#12345" }))).toBeNull()
  })

  it("never offers a colour picker in place of a secret, a list, or a read-only row", () => {
    expect(colourValue(field({ key: "good", path: "good", value: "#8ba888", secret: true }))).toBeNull()
    expect(colourValue(field({ key: "good", path: "good", value: "#8ba888", kind: "LIST" }))).toBeNull()
    expect(
      colourValue(field({ key: "good", path: "good", value: "#8ba888", editable: false })),
    ).toBeNull()
  })
})

describe("ColourControl", () => {
  it("shows the hex value as text and lets it be typed, not only dragged on a wheel", () => {
    const onChange = vi.fn()
    render(<ColourControl id="good" value="#8ba888" disabled={false} onChange={onChange} />)

    // Not `getByDisplayValue` - the native swatch's own value is "#8ba888" too, so that query
    // matches both inputs. `getByRole("textbox")` only matches the plain text field: an
    // `input[type="color"]` has no such role.
    const text = screen.getByRole("textbox") as HTMLInputElement
    fireEvent.change(text, { target: { value: "#123456" } })

    expect(onChange).toHaveBeenCalledWith("#123456")
  })

  it("also writes through the native swatch", () => {
    const onChange = vi.fn()
    render(<ColourControl id="good" value="#8ba888" disabled={false} onChange={onChange} />)

    const swatch = screen.getByLabelText("Pick a colour") as HTMLInputElement
    expect(swatch.value).toBe("#8ba888")
    fireEvent.change(swatch, { target: { value: "#00ff00" } })

    expect(onChange).toHaveBeenCalledWith("#00ff00")
  })

  /** The preview starts hidden since the third round, so every test about it opens it first. */
  const showPreview = () => fireEvent.click(screen.getByRole("button", { name: /preview/i }))

  it("previews the colour on Minecraft's own chat background, not a plain white field", () => {
    render(<ColourControl id="good" value="#8ba888" disabled={false} onChange={vi.fn()} />)
    showPreview()

    const preview = screen.getByRole("img", { name: /preview on minecraft's chat background/i })
    expect(preview.style.backgroundColor).toBe(MINECRAFT_CHAT_BACKGROUND)

    const sample = screen.getByText(SAMPLE_TEXT)
    // jsdom normalises an inline `color` style to `rgb(...)` regardless of how it was written -
    // "#8ba888" is (139, 168, 136) in decimal.
    expect(sample.style.color).toBe("rgb(139, 168, 136)")
  })

  /**
   * steward/63, second round. Till, 2026-09-17: it works, but the text preview under each field is
   * not nice to look at - without saying which part. Photographed at 390px before anything changed
   * (`/home/dev/ui-shots/shots/steward-63-colours-390-before.png`): a five-word sample in a column
   * 112-150px wide wrapped onto three lines, so the five tones of one `colours.yml` stood in a row
   * of five different heights, and the last one - alone on its own line and therefore full width -
   * was one line high. Nothing about the colours was comparable, which is the whole point of
   * drawing them side by side.
   *
   * One word cannot wrap, and `truncate` is what holds that when a column is narrower still.
   */
  it("keeps the sample to one line, so a row of colours is a row of equal heights", () => {
    render(<ColourControl id="good" value="#8ba888" disabled={false} onChange={vi.fn()} />)
    showPreview()

    const sample = screen.getByText(SAMPLE_TEXT)
    expect(SAMPLE_TEXT.includes(" ")).toBe(false)
    expect(sample.className).toContain("truncate")
  })

  /**
   * steward/63, third round. Till, 2026-09-18: the preview spans the whole width of the field, the
   * way the alternative drew it - but it starts hidden, and an eye in the hex field is what brings
   * it out. A preview nobody asked for is a band of colour on every row of a settings page; asked
   * for, it is worth the full width.
   */
  it("draws no preview until it is asked for", () => {
    render(<ColourControl id="good" value="#8ba888" disabled={false} onChange={vi.fn()} />)

    expect(screen.queryByText(SAMPLE_TEXT)).toBeNull()
  })

  it("brings the preview out on the eye, and takes it back on a second press", () => {
    render(<ColourControl id="good" value="#8ba888" disabled={false} onChange={vi.fn()} />)
    const eye = screen.getByRole("button", { name: /preview/i })

    fireEvent.click(eye)
    expect(screen.queryByText(SAMPLE_TEXT)).not.toBeNull()

    // The label flips with the state, so the same query finds it either way.
    fireEvent.click(eye)
    expect(screen.queryByText(SAMPLE_TEXT)).toBeNull()
  })

  it("gives the shown preview the whole width of the field, not a band the size of the word", () => {
    render(<ColourControl id="good" value="#8ba888" disabled={false} onChange={vi.fn()} />)
    showPreview()

    const preview = screen.getByRole("img", { name: /preview on minecraft's chat background/i })
    expect(preview.className).toContain("w-full")
    expect(preview.className).not.toContain("w-fit")
  })

  it("shows the sample dimmed, and no instructions, while the value is not a colour yet", () => {
    // Typing "#8ba" (still incomplete) must not crash the native colour input, which refuses
    // anything that is not exactly seven characters - the swatch falls back to a placeholder. The
    // preview, once open, keeps its place and its height rather than swapping in a sentence: the
    // box is the same size either way, so nothing below it moves while somebody retypes six digits.
    render(<ColourControl id="good" value="#8ba" disabled={false} onChange={vi.fn()} />)
    showPreview()

    const swatch = screen.getByLabelText("Pick a colour") as HTMLInputElement
    expect(swatch.value).toBe("#000000")
    const sample = screen.getByText(SAMPLE_TEXT)
    expect(sample.style.color).toBe("")
    expect(screen.queryByText(/type a hex value/i)).toBeNull()
    // The name still says what a sighted reader sees from the dimming alone.
    screen.getByRole("img", { name: /no valid colour to preview yet/i })
  })
})

describe("colourRuns", () => {
  const isColour = (entry: ConfigEntry) => colourValue(entry) !== null

  it("groups every colour.yml entry into a single row (season-2-ingame/22's five tones)", () => {
    const entries = [
      field({ key: "good", path: "good", value: "#8ba888" }),
      field({ key: "bad", path: "bad", value: "#a8888b" }),
      field({ key: "warn", path: "warn", value: "#b08a4a" }),
      field({ key: "neutral", path: "neutral", value: "#c9c9c9" }),
      field({ key: "muted", path: "muted", value: "#aaaaaa" }),
    ]

    const runs = colourRuns(entries, isColour)

    expect(runs).toHaveLength(1)
    expect(runs[0].map((entry) => entry.key)).toEqual(["good", "bad", "warn", "neutral", "muted"])
  })

  it("never groups a single colour on its own - a row is at least two", () => {
    const entries = [
      field({ key: "base-url", path: "base-url", value: "http://example" }),
      field({ key: "good", path: "good", value: "#8ba888" }),
      field({ key: "timeout", path: "timeout", value: "30" }),
    ]

    expect(colourRuns(entries, isColour)).toHaveLength(0)
  })

  it("a non-colour entry between two colours splits them into two single fields, not one row", () => {
    const entries = [
      field({ key: "good", path: "good", value: "#8ba888" }),
      field({ key: "label", path: "label", value: "not a colour" }),
      field({ key: "bad", path: "bad", value: "#a8888b" }),
    ]

    expect(colourRuns(entries, isColour)).toHaveLength(0)
  })

  it("keeps colours under different parents in separate rows", () => {
    const entries = [
      field({ key: "good", path: "a.good", value: "#8ba888" }),
      field({ key: "bad", path: "a.bad", value: "#a8888b" }),
      field({ key: "good", path: "b.good", value: "#111111" }),
      field({ key: "bad", path: "b.bad", value: "#222222" }),
    ]

    const runs = colourRuns(entries, isColour)

    expect(runs).toHaveLength(2)
    expect(runs[0].map((entry) => entry.path)).toEqual(["a.good", "a.bad"])
    expect(runs[1].map((entry) => entry.path)).toEqual(["b.good", "b.bad"])
  })

  it("groups the thirteen prestige tiers into one row and leaves admin out of it (season-2-ingame/23)", () => {
    // The exact shape smp/smp/prestige-colours.yml reads back as from the live worker on
    // 2026-09-16: `admin` is a sibling scalar at the top level - not a fourteenth tier - and the
    // thirteen tiers sit under `prestige`, consecutive and nothing else between them. This is the
    // claim steward/63's own comment made before this file existed ("all thirteen prestige colours
    // ... not yet shipped"); this test is what proves it rather than merely asserting it.
    const entries = [
      field({ key: "admin", path: "admin", value: "#ff5555" }),
      field({ key: "tier-01", path: "prestige.tier-01", value: "#5fbfae" }),
      field({ key: "tier-02", path: "prestige.tier-02", value: "#5ea9d6" }),
      field({ key: "tier-03", path: "prestige.tier-03", value: "#6f93e0" }),
      field({ key: "tier-04", path: "prestige.tier-04", value: "#8f83e6" }),
      field({ key: "tier-05", path: "prestige.tier-05", value: "#a878e0" }),
      field({ key: "tier-06", path: "prestige.tier-06", value: "#c96fd6" }),
      field({ key: "tier-07", path: "prestige.tier-07", value: "#dd6fae" }),
      field({ key: "tier-08", path: "prestige.tier-08", value: "#e07d78" }),
      field({ key: "tier-09", path: "prestige.tier-09", value: "#e2984f" }),
      field({ key: "tier-10", path: "prestige.tier-10", value: "#dbb043" }),
      field({ key: "tier-11", path: "prestige.tier-11", value: "#e8d35a" }),
      field({ key: "tier-12", path: "prestige.tier-12", value: "#f0dc70" }),
      field({ key: "tier-13", path: "prestige.tier-13", value: "#fff6d8" }),
    ]

    const runs = colourRuns(entries, isColour)

    expect(runs).toHaveLength(1)
    expect(runs[0]).toHaveLength(13)
    expect(runs[0].map((entry) => entry.key)).toEqual([
      "tier-01", "tier-02", "tier-03", "tier-04", "tier-05", "tier-06", "tier-07",
      "tier-08", "tier-09", "tier-10", "tier-11", "tier-12", "tier-13",
    ])
    expect(runs[0].some((entry) => entry.key === "admin")).toBe(false)
  })

  it("drops a blank member out of the run rather than guessing it is a colour too", () => {
    // The documented cost of deciding by value: a colour saved blank splits the run exactly the
    // way an unrelated field would. Fixed by a schema "colour" kind (steward/54), not by this
    // function guessing harder.
    const entries = [
      field({ key: "good", path: "good", value: "#8ba888" }),
      field({ key: "bad", path: "bad", value: "" }),
      field({ key: "warn", path: "warn", value: "#b08a4a" }),
    ]

    expect(colourRuns(entries, isColour)).toHaveLength(0)
  })
})
