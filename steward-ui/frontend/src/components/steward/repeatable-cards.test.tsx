import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import {
  RepeatableCards,
  blankSection,
  sectionsFromEntry,
} from "@/components/steward/repeatable-cards"
import type { ConfigEntry } from "@/lib/api"

/**
 * `RepeatableCards` is the mechanism steward/57 asked for: one card per entry of a repeating
 * structure, add and remove, fields drawn from the schema like any other field. `languages` in
 * `discord-bot/access.yml` (steward/49) is the motivating case, but the worker does not yet send
 * the shape this component needs - see the long comment on `ConfigEntry.kind` in `lib/api.ts`.
 * These tests exercise the component against fixtures shaped the way that comment proposes.
 *
 * Every case here is a RED test first, recorded failing before `repeatable-cards.tsx` existed.
 */

function field(over: Partial<ConfigEntry> & { key: string }): ConfigEntry {
  return {
    path: over.key,
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

const TEMPLATE: ConfigEntry[] = [field({ key: "tag", label: "Tag" }), field({ key: "role", label: "Role" })]

function sectionsEntry(sections: ConfigEntry[][], template: ConfigEntry[] = TEMPLATE): ConfigEntry {
  return {
    ...field({ key: "languages", label: "Languages" }),
    path: "languages",
    kind: "SECTIONS",
    editable: true,
    template,
    sections,
  }
}

afterEach(cleanup)

describe("sectionsFromEntry", () => {
  it("reads one flat record per section, keyed by each field's own key", () => {
    const entry = sectionsEntry([
      [field({ key: "tag", value: "en" }), field({ key: "role", value: "123" })],
    ])
    expect(sectionsFromEntry(entry)).toEqual([{ tag: "en", role: "123" }])
  })

  it("is empty for an empty list", () => {
    expect(sectionsFromEntry(sectionsEntry([]))).toEqual([])
  })
})

describe("blankSection", () => {
  it("has every template key, all empty", () => {
    expect(blankSection(TEMPLATE)).toEqual({ tag: "", role: "" })
  })
})

describe("RepeatableCards", () => {
  it("draws one card per entry, in order, with a field for every template key", () => {
    const entry = sectionsEntry([
      [field({ key: "tag", value: "en" }), field({ key: "role", value: "" })],
      [field({ key: "tag", value: "de" }), field({ key: "role", value: "" })],
    ])
    render(
      <RepeatableCards
        entry={entry}
        value={sectionsFromEntry(entry)}
        disabled={false}
        roles={undefined}
        channels={undefined}
        onChange={() => {}}
      />,
    )

    const tags = screen.getAllByLabelText("Tag") as HTMLInputElement[]
    expect(tags.map((input) => input.value)).toEqual(["en", "de"])
  })

  it("adds a blank card at the end, built from the template", () => {
    const entry = sectionsEntry([[field({ key: "tag", value: "en" }), field({ key: "role" })]])
    const onChange = vi.fn()
    render(
      <RepeatableCards
        entry={entry}
        value={sectionsFromEntry(entry)}
        disabled={false}
        roles={undefined}
        channels={undefined}
        onChange={onChange}
      />,
    )

    fireEvent.click(screen.getByRole("button", { name: /Add entry/ }))

    expect(onChange).toHaveBeenCalledWith([
      { tag: "en", role: "" },
      { tag: "", role: "" },
    ])
  })

  it("removes only from the drafted value - it is on the caller to decide when that reaches the file", () => {
    const entry = sectionsEntry([
      [field({ key: "tag", value: "en" }), field({ key: "role" })],
      [field({ key: "tag", value: "de" }), field({ key: "role" })],
    ])
    const onChange = vi.fn()
    render(
      <RepeatableCards
        entry={entry}
        value={sectionsFromEntry(entry)}
        disabled={false}
        roles={undefined}
        channels={undefined}
        onChange={onChange}
      />,
    )

    fireEvent.click(screen.getByRole("button", { name: "Remove entry 1" }))

    expect(onChange).toHaveBeenCalledWith([{ tag: "de", role: "" }])
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it("edits a field within a card by calling onChange with the whole updated list", () => {
    const entry = sectionsEntry([[field({ key: "tag", value: "en" }), field({ key: "role", value: "" })]])
    const onChange = vi.fn()
    render(
      <RepeatableCards
        entry={entry}
        value={sectionsFromEntry(entry)}
        disabled={false}
        roles={undefined}
        channels={undefined}
        onChange={onChange}
      />,
    )

    fireEvent.change(screen.getByLabelText("Tag"), { target: { value: "fr" } })

    expect(onChange).toHaveBeenCalledWith([{ tag: "fr", role: "" }])
  })

  it("puts a channel field through the SnowflakePicker's fallback, not a plain text input", () => {
    const template = [
      field({ key: "tag", label: "Tag" }),
      field({ key: "contribution-channel", label: "Contribution channel" }),
    ]
    const entry = sectionsEntry(
      [[field({ key: "tag", value: "en" }), field({ key: "contribution-channel", value: "" })]],
      template,
    )
    render(
      <RepeatableCards
        entry={entry}
        value={sectionsFromEntry(entry)}
        disabled={false}
        roles={undefined}
        channels={{ available: false, reason: "no bot token in this test", entries: [] }}
        onChange={() => {}}
      />,
    )

    // Without an available directory, SnowflakePicker degrades to a text input but keeps its own
    // hint underneath it - the plain ScalarControl text branch never renders this sentence.
    screen.getByText(/Paste the id instead/)
  })

  it("falls back to raw text instead of a card when the schema names no template", () => {
    const entry = sectionsEntry([[field({ key: "tag", value: "en" })]], [])
    render(
      <RepeatableCards
        entry={entry}
        value={sectionsFromEntry(entry)}
        disabled={false}
        roles={undefined}
        channels={undefined}
        onChange={() => {}}
      />,
    )

    screen.getByText(/no card fits/i)
    expect(screen.queryByRole("button", { name: /Add entry/ })).toBeNull()
  })
})
