import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { ColourControl, MINECRAFT_CHAT_BACKGROUND, colourRuns } from "@/components/steward/colour-control"
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

  it("previews the colour on Minecraft's own chat background, not a plain white field", () => {
    render(<ColourControl id="good" value="#8ba888" disabled={false} onChange={vi.fn()} />)

    const preview = screen.getByRole("img", { name: /preview on minecraft's chat background/i })
    expect(preview.style.backgroundColor).toBe(MINECRAFT_CHAT_BACKGROUND)

    const sample = screen.getByText("Nordtal — sample chat text")
    // jsdom normalises an inline `color` style to `rgb(...)` regardless of how it was written -
    // "#8ba888" is (139, 168, 136) in decimal.
    expect(sample.style.color).toBe("rgb(139, 168, 136)")
  })

  it("shows no colour preview for a value that is not a valid hex colour yet, rather than a broken swatch", () => {
    // Typing "#8ba" (still incomplete) must not crash the native colour input, which refuses
    // anything that is not exactly seven characters - the swatch falls back to a placeholder and
    // the preview says a value has not been typed yet, rather than colouring "#8ba" literally as
    // one of the two things it could otherwise be misread as (short hex, or malformed).
    render(<ColourControl id="good" value="#8ba" disabled={false} onChange={vi.fn()} />)

    const swatch = screen.getByLabelText("Pick a colour") as HTMLInputElement
    expect(swatch.value).toBe("#000000")
    screen.getByText("type a hex value to preview it")
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
