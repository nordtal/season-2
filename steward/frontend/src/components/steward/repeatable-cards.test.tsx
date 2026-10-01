import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { RepeatableCards, blankSection, sectionsFromEntry } from "@/components/steward/repeatable-cards"
import type { SectionValues } from "@/components/steward/repeatable-cards"
import type { ConfigEntry } from "@/lib/api"
import { asButton, asInput } from "@/lib/test-elements"

/** `RepeatableCards` against fixtures of a repeating structure, one card per entry with add and remove. */

function field(over: Partial<ConfigEntry> & { key: string }): ConfigEntry {
  return {
    path: over.key,
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
    const entry = sectionsEntry([[field({ key: "tag", value: "en" }), field({ key: "role", value: "123" })]])
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

    const tags = screen.getAllByLabelText("Tag").map(asInput)
    expect(tags.map((input) => input.value)).toEqual(["en", "de"])
  })

  it("adds a blank card at the end, built from the template", () => {
    const entry = sectionsEntry([[field({ key: "tag", value: "en" }), field({ key: "role" })]])
    const onChange = vi.fn<(value: SectionValues[]) => void>()
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
    const onChange = vi.fn<(value: SectionValues[]) => void>()
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
    /** The click only arms the confirmation, so nothing is drafted yet. */
    expect(onChange).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole("button", { name: "Remove it" }))

    expect(onChange).toHaveBeenCalledWith([{ tag: "de", role: "" }])
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it("edits a field within a card by calling onChange with the whole updated list", () => {
    const entry = sectionsEntry([[field({ key: "tag", value: "en" }), field({ key: "role", value: "" })]])
    const onChange = vi.fn<(value: SectionValues[]) => void>()
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

    /** Without a directory, SnowflakePicker falls back to a text input and keeps its own hint. */
    expect(screen.getByText(/Paste the id instead/)).toBeTruthy()
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

/** Removing an entry confirms with the list's own explanation, the same dialog for every unprotected entry. */
describe("RepeatableCards - confirming a removal", () => {
  it("does not touch the draft on the trash icon alone - it opens a confirmation first", () => {
    const entry = sectionsEntry([[field({ key: "tag", value: "en" }), field({ key: "role" })]])
    const onChange = vi.fn<(value: SectionValues[]) => void>()
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

    expect(onChange).not.toHaveBeenCalled()
    screen.getByRole("alertdialog")
  })

  it("shows the list's own explanation in the confirmation, not a value-specific warning", () => {
    const entry = {
      ...sectionsEntry([[field({ key: "tag", value: "en" }), field({ key: "role" })]]),
      explanation: "'en' must be present - it is the fallback everything degrades to",
    }
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

    fireEvent.click(screen.getByRole("button", { name: "Remove entry 1" }))

    expect(screen.getByText(/fallback everything degrades to/)).toBeTruthy()
  })

  it("cancelling leaves the draft untouched", () => {
    const entry = sectionsEntry([[field({ key: "tag", value: "en" }), field({ key: "role" })]])
    const onChange = vi.fn<(value: SectionValues[]) => void>()
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
    fireEvent.click(screen.getByRole("button", { name: "Keep it" }))

    expect(onChange).not.toHaveBeenCalled()
    expect(screen.queryByRole("alertdialog")).toBeNull()
  })
})

/** The entry `@Protected` names cannot be removed; steward refuses it too, so the disabled button is a courtesy. */
describe("RepeatableCards - a protected entry", () => {
  it("disables the trash icon for the entry the schema names, and leaves every other one alone", () => {
    const entry = {
      ...sectionsEntry([
        [field({ key: "tag", value: "en" }), field({ key: "role" })],
        [field({ key: "tag", value: "de" }), field({ key: "role" })],
      ]),
      protectedEntry: { field: "tag", value: "en" },
    }
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

    const protectedButton = asButton(screen.getByRole("button", { name: "Entry 1 cannot be removed" }))
    const removableButton = asButton(screen.getByRole("button", { name: "Remove entry 2" }))

    expect(protectedButton.disabled).toBe(true)
    expect(removableButton.disabled).toBe(false)
  })

  it("clicking the disabled button opens no confirmation - there is nothing to confirm", () => {
    const entry = {
      ...sectionsEntry([[field({ key: "tag", value: "en" }), field({ key: "role" })]]),
      protectedEntry: { field: "tag", value: "en" },
    }
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

    fireEvent.click(screen.getByRole("button", { name: "Entry 1 cannot be removed" }))

    expect(screen.queryByRole("alertdialog")).toBeNull()
  })

  it("does not disable anything when the schema's protected value matches no current entry", () => {
    /** The value is only removed once it stops matching, since a protected tag nothing carries protects nothing. */
    const entry = {
      ...sectionsEntry([[field({ key: "tag", value: "de" }), field({ key: "role" })]]),
      protectedEntry: { field: "tag", value: "en" },
    }
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

    const button = asButton(screen.getByRole("button", { name: "Remove entry 1" }))
    expect(button.disabled).toBe(false)
  })
})

/** A title only when the caller supplies one; every other entry keeps "Entry N". */
describe("RepeatableCards - a caller-supplied title", () => {
  it("uses it instead of the plain index", () => {
    const entry = sectionsEntry([[field({ key: "tag", value: "en" }), field({ key: "role" })]])
    render(
      <RepeatableCards
        entry={entry}
        value={sectionsFromEntry(entry)}
        disabled={false}
        roles={undefined}
        channels={undefined}
        onChange={() => {}}
        sectionTitle={(section) => `Language: ${typeof section.tag === "string" ? section.tag : ""}`}
      />,
    )

    expect(screen.getByText("Language: en")).toBeTruthy()
    expect(screen.queryByText("Entry 1")).toBeNull()
  })

  it("keeps the plain index when no title is supplied", () => {
    const entry = sectionsEntry([[field({ key: "tag", value: "en" }), field({ key: "role" })]])
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

    expect(screen.getByText("Entry 1")).toBeTruthy()
  })
})

/** A card says when it misses a required channel, decided by `isRequiredChannel` and not by its section. */
describe("RepeatableCards - an incomplete card", () => {
  const REQUIRED_CHANNEL = field({
    key: "contribution-channel",
    label: "Contribution channel",
    explanation: "Carries the buy-access message in this language, and its donation thank-yous.",
  })
  const OPTIONAL_CHANNEL = field({
    key: "announcement-channel",
    label: "Announcement channel",
    explanation: "OPTIONAL, like status-channel: empty means this language gets no announcements.",
  })

  it("says so, and names the missing field, when a required channel is blank", () => {
    const template = [field({ key: "tag", label: "Tag" }), REQUIRED_CHANNEL]
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
        channels={undefined}
        onChange={() => {}}
      />,
    )

    screen.getByText(/incomplete/i)
    /** Named as the field's label and again in the "missing" sentence. */
    expect(screen.getAllByText(/Contribution channel/)).toHaveLength(2)
  })

  it("says nothing when every required channel is filled", () => {
    const template = [field({ key: "tag", label: "Tag" }), REQUIRED_CHANNEL]
    const entry = sectionsEntry(
      [[field({ key: "tag", value: "en" }), field({ key: "contribution-channel", value: "123" })]],
      template,
    )
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

    expect(screen.queryByText(/incomplete/i)).toBeNull()
  })

  it("does not call an empty OPTIONAL channel incomplete", () => {
    const template = [field({ key: "tag", label: "Tag" }), OPTIONAL_CHANNEL]
    const entry = sectionsEntry(
      [[field({ key: "tag", value: "en" }), field({ key: "announcement-channel", value: "" })]],
      template,
    )
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

    expect(screen.queryByText(/incomplete/i)).toBeNull()
  })
})

describe("RepeatableCards - sections inside sections", () => {
  /** The milestone track: objectives within milestones, items within objectives, as deep as the schema. */
  const OBJECTIVE: ConfigEntry[] = [
    field({ key: "key", label: "ID" }),
    field({ key: "target", label: "Target", type: "INTEGER" }),
    field({ key: "items", label: "Items", kind: "LIST" }),
  ]
  const MILESTONE: ConfigEntry[] = [
    field({ key: "key", label: "ID" }),
    field({ key: "objectives", label: "Objectives", kind: "SECTIONS", template: OBJECTIVE }),
  ]

  function track(): ConfigEntry {
    return sectionsEntry(
      [
        [
          field({ key: "key", value: "foothold" }),
          field({
            key: "objectives",
            kind: "SECTIONS",
            template: OBJECTIVE,
            sections: [
              [
                field({ key: "key", value: "logs" }),
                field({ key: "target", value: "64" }),
                field({ key: "items", kind: "LIST", items: ["OAK_LOG", "SPRUCE_LOG"] }),
              ],
            ],
          }),
        ],
        [
          field({ key: "key", value: "waiting" }),
          field({ key: "objectives", kind: "SECTIONS", template: OBJECTIVE, sections: [] }),
        ],
      ],
      MILESTONE,
    )
  }

  it("reads lists and nested sections as lists, not as text", () => {
    expect(sectionsFromEntry(track())).toEqual([
      { key: "foothold", objectives: [{ key: "logs", target: "64", items: ["OAK_LOG", "SPRUCE_LOG"] }] },
      { key: "waiting", objectives: [] },
    ])
  })

  it("gives a new entry an empty list where the template holds one", () => {
    expect(blankSection(MILESTONE)).toEqual({ key: "", objectives: [] })
    expect(blankSection(OBJECTIVE)).toEqual({ key: "", target: "", items: [] })
  })

  it("titles every card with its key, at every level", () => {
    const entry = track()
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
    expect(screen.getByText("foothold")).toBeTruthy()
    expect(screen.getByText("waiting")).toBeTruthy()
    expect(screen.getByText("logs")).toBeTruthy()
    expect(screen.getByDisplayValue("SPRUCE_LOG")).toBeTruthy()
  })

  it("edits an item of an objective as part of the whole track", () => {
    const entry = track()
    const onChange = vi.fn<(value: SectionValues[]) => void>()
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
    fireEvent.change(screen.getByDisplayValue("SPRUCE_LOG"), { target: { value: "BIRCH_LOG" } })
    expect(onChange).toHaveBeenCalledWith([
      { key: "foothold", objectives: [{ key: "logs", target: "64", items: ["OAK_LOG", "BIRCH_LOG"] }] },
      { key: "waiting", objectives: [] },
    ])
  })

  it("adds an objective to the one milestone it was added to", () => {
    const entry = track()
    const onChange = vi.fn<(value: SectionValues[]) => void>()
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
    fireEvent.click(screen.getByRole("button", { name: "Add to waiting" }))
    expect(onChange).toHaveBeenCalledWith([
      { key: "foothold", objectives: [{ key: "logs", target: "64", items: ["OAK_LOG", "SPRUCE_LOG"] }] },
      { key: "waiting", objectives: [{ key: "", target: "", items: [] }] },
    ])
  })

  it("draws no card inside a card", () => {
    const entry = track()
    const { container } = render(
      <RepeatableCards
        entry={entry}
        value={sectionsFromEntry(entry)}
        disabled={false}
        roles={undefined}
        channels={undefined}
        onChange={() => {}}
      />,
    )
    expect(container.querySelectorAll('[data-slot="card"] [data-slot="card"]').length).toBe(0)
  })
})
