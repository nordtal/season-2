import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { ColourControl, MINECRAFT_CHAT_BACKGROUND, SAMPLE_TEXT, colourRuns } from "@/components/steward/colour-control"
import { colourValue } from "@/components/steward/config-controls"
import type { ConfigEntry } from "@/lib/api"
import { asInput } from "@/lib/test-elements"

/** The colour picker, `colourValue`, which offers it by the value's shape, and `colourRuns`, which rows them. */

/** The preview starts hidden, so every test about it opens it first. */
const showPreview = () => fireEvent.click(screen.getByRole("button", { name: /preview/i }))

function field(over: Partial<ConfigEntry> & { key: string; path: string }): ConfigEntry {
  return {
    label: over.key,
    explanation: "",
    noExplanationNeeded: false,
    filled: true,
    value: "",
    items: [],
    kind: "SCALAR",
    type: "STRING",
    editable: true,
    secret: false,
    ...over,
  }
}

afterEach(cleanup)

describe("colourValue", () => {
  it("recognises a stored value shaped like #rrggbb", () => {
    expect(colourValue(field({ key: "good", path: "good", value: "#8ba888" }))).toBe("#8ba888")
    // Case insensitive: jcore writes lower case, a hand edited file need not.
    expect(colourValue(field({ key: "good", path: "good", value: "#8BA888" }))).toBe("#8BA888")
  })

  it("never mistakes an empty value for a colour", () => {
    /** Both "" and undefined are asserted, so inlining the regex cannot quietly drop the early return. */
    expect(colourValue(field({ key: "good", path: "good", value: "" }))).toBeNull()
  })

  it("rejects an ordinary text value, including one that starts with #", () => {
    expect(colourValue(field({ key: "note", path: "note", value: "just text" }))).toBeNull()
    /** Seven and five digits are rejected, as well as six. */
    expect(colourValue(field({ key: "note", path: "note", value: "#1234567" }))).toBeNull()
    expect(colourValue(field({ key: "note", path: "note", value: "#12345" }))).toBeNull()
  })

  it("never offers a colour picker in place of a secret, a list, or a read-only row", () => {
    expect(colourValue(field({ key: "good", path: "good", value: "#8ba888", secret: true }))).toBeNull()
    expect(colourValue(field({ key: "good", path: "good", value: "#8ba888", kind: "LIST" }))).toBeNull()
    expect(colourValue(field({ key: "good", path: "good", value: "#8ba888", editable: false }))).toBeNull()
  })
})

describe("ColourControl", () => {
  it("shows the hex value as text and lets it be typed, not only dragged on a wheel", () => {
    const onChange = vi.fn<(value: string) => void>()
    render(<ColourControl id="good" value="#8ba888" disabled={false} onChange={onChange} />)

    /** `getByRole("textbox")`, since the native swatch holds the same display value but has no such role. */
    const text = asInput(screen.getByRole("textbox"))
    fireEvent.change(text, { target: { value: "#123456" } })

    expect(onChange).toHaveBeenCalledWith("#123456")
  })

  it("also writes through the native swatch", () => {
    const onChange = vi.fn<(value: string) => void>()
    render(<ColourControl id="good" value="#8ba888" disabled={false} onChange={onChange} />)

    const swatch = asInput(screen.getByLabelText("Pick a colour"))
    expect(swatch.value).toBe("#8ba888")
    fireEvent.change(swatch, { target: { value: "#00ff00" } })

    expect(onChange).toHaveBeenCalledWith("#00ff00")
  })

  it("previews the colour on Minecraft's own chat background, not a plain white field", () => {
    render(<ColourControl id="good" value="#8ba888" disabled={false} onChange={vi.fn<(value: string) => void>()} />)
    showPreview()

    const preview = screen.getByRole("img", { name: /preview on minecraft's chat background/i })
    expect(preview.style.backgroundColor).toBe(MINECRAFT_CHAT_BACKGROUND)

    const sample = screen.getByText(SAMPLE_TEXT)
    /** jsdom normalises an inline `color` to `rgb(...)`; "#8ba888" is (139, 168, 136). */
    expect(sample.style.color).toBe("rgb(139, 168, 136)")
  })

  /** A wrapped sample would make one colour in a row taller than the rest. */
  it("keeps the sample to one line, so a row of colours is a row of equal heights", () => {
    render(<ColourControl id="good" value="#8ba888" disabled={false} onChange={vi.fn<(value: string) => void>()} />)
    showPreview()

    const sample = screen.getByText(SAMPLE_TEXT)
    expect(SAMPLE_TEXT.includes(" ")).toBe(false)
    expect(sample.className).toContain("truncate")
  })

  /** The preview spans the field's width but starts hidden behind the eye. */
  it("draws no preview until it is asked for", () => {
    render(<ColourControl id="good" value="#8ba888" disabled={false} onChange={vi.fn<(value: string) => void>()} />)

    expect(screen.queryByText(SAMPLE_TEXT)).toBeNull()
  })

  it("brings the preview out on the eye, and takes it back on a second press", () => {
    render(<ColourControl id="good" value="#8ba888" disabled={false} onChange={vi.fn<(value: string) => void>()} />)
    const eye = screen.getByRole("button", { name: /preview/i })

    fireEvent.click(eye)
    expect(screen.queryByText(SAMPLE_TEXT)).not.toBeNull()

    // The label flips with the state, so the same query finds it either way.
    fireEvent.click(eye)
    expect(screen.queryByText(SAMPLE_TEXT)).toBeNull()
  })

  it("gives the shown preview the whole width of the field, not a band the size of the word", () => {
    render(<ColourControl id="good" value="#8ba888" disabled={false} onChange={vi.fn<(value: string) => void>()} />)
    showPreview()

    const preview = screen.getByRole("img", { name: /preview on minecraft's chat background/i })
    expect(preview.className).toContain("w-full")
    expect(preview.className).not.toContain("w-fit")
  })

  it("shows the sample dimmed, and no instructions, while the value is not a colour yet", () => {
    /** An incomplete value must not crash the native input, and the preview keeps its size meanwhile. */
    render(<ColourControl id="good" value="#8ba" disabled={false} onChange={vi.fn<(value: string) => void>()} />)
    showPreview()

    const swatch = asInput(screen.getByLabelText("Pick a colour"))
    expect(swatch.value).toBe("#000000")
    const sample = screen.getByText(SAMPLE_TEXT)
    expect(sample.style.color).toBe("")
    expect(screen.queryByText(/type a hex value/i)).toBeNull()
    // The name still says what a sighted reader sees from the dimming alone.
    screen.getByRole("img", { name: /no valid colour to preview yet/i })
  })
})

const isColour = (entry: ConfigEntry) => colourValue(entry) !== null

describe("colourRuns", () => {
  it("groups every colour.yml entry into a single row, one for each of the five tones", () => {
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

  it("groups the thirteen prestige tiers into one row and leaves admin out of it", () => {
    /** The shape of `smp/smp/prestige-colours.yml`: `admin` at the top, thirteen tiers under `prestige`. */
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
      "tier-01",
      "tier-02",
      "tier-03",
      "tier-04",
      "tier-05",
      "tier-06",
      "tier-07",
      "tier-08",
      "tier-09",
      "tier-10",
      "tier-11",
      "tier-12",
      "tier-13",
    ])
    expect(runs[0].some((entry) => entry.key === "admin")).toBe(false)
  })

  it("drops a blank member out of the run rather than guessing it is a colour too", () => {
    /** A colour saved blank splits the run like an unrelated field would, the cost of deciding by value. */
    const entries = [
      field({ key: "good", path: "good", value: "#8ba888" }),
      field({ key: "bad", path: "bad", value: "" }),
      field({ key: "warn", path: "warn", value: "#b08a4a" }),
    ]

    expect(colourRuns(entries, isColour)).toHaveLength(0)
  })
})
