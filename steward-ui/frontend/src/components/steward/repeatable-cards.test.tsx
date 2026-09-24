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
    // The click arms a confirmation rather than removing straight away (see the describe block
    // below) - nothing is drafted until that confirmation is answered.
    expect(onChange).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole("button", { name: "Remove it" }))

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

/**
 * Removing an entry asks first, and what it asks is the list's own explanation - the generic
 * confirmation steward/49 and steward/61 built for every `SECTIONS` list, protected or not. Nothing
 * here knows the word "English" or the tag "en"; it shows whatever `explanationOf(entry)` already
 * carries for the parent list. The entry a schema actually marks `@Protected` (steward/74) never
 * reaches this dialog at all - see the describe block below - so this one stays the generic answer
 * for every entry that is not that one.
 */
describe("RepeatableCards - confirming a removal", () => {
  it("does not touch the draft on the trash icon alone - it opens a confirmation first", () => {
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

    screen.getByText(/fallback everything degrades to/)
  })

  it("cancelling leaves the draft untouched", () => {
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

    fireEvent.click(screen.getByRole("button", { name: "Remove entry 1" }))
    fireEvent.click(screen.getByRole("button", { name: "Keep it" }))

    expect(onChange).not.toHaveBeenCalled()
    expect(screen.queryByRole("alertdialog")).toBeNull()
  })
})

/**
 * The one entry a schema's `@Protected` names (steward/74) cannot be removed from here at all - the
 * worker refuses the same removal (`ConfigFiles.removeSection`), so disabling the button up front
 * is a courtesy rather than the enforcement: the interface no longer lets somebody confirm a
 * removal that would only fail once the save reached the worker.
 */
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

    const protectedButton = screen.getByRole("button", {
      name: "Entry 1 cannot be removed",
    }) as HTMLButtonElement
    const removableButton = screen.getByRole("button", { name: "Remove entry 2" }) as HTMLButtonElement

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
    // The value is only removed once it stops matching - a schema still marking "en" as protected
    // while nothing tagged "en" survives in the draft protects nothing right now, and nothing here
    // should pretend otherwise.
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

    const button = screen.getByRole("button", { name: "Remove entry 1" }) as HTMLButtonElement
    expect(button.disabled).toBe(false)
  })
})

/**
 * A card's title, when the caller supplies one (steward/61) - `configuration.tsx` is the only
 * caller that does, keyed on the `languages` path, because no field in an arbitrary schema is
 * marked as "the one that names this entry". Every other `SECTIONS` entry (`tiers`, today) gets no
 * such prop and keeps the plain "Entry N" every card has always had.
 */
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
        sectionTitle={(section) => `Language: ${section.tag}`}
      />,
    )

    screen.getByText("Language: en")
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

    screen.getByText("Entry 1")
  })
})

/**
 * A card visibly says when it is missing a channel it needs (steward/61's "visibly incomplete, not
 * merely empty") - generic on `isRequiredChannel` from `config-controls.tsx`, which itself decides
 * from the field's key (is it a channel at all) and the schema's own explanation (does it say
 * OPTIONAL), never from which section it happens to sit in.
 */
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
    // Named twice - once as the field's own label, once inside the "missing" sentence - so this
    // checks there are two rather than exactly one.
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
  // The track in `smp/milestones.yml`: milestones, each with a list of objectives, each objective
  // with a list of items. The template goes as deep as the schema does.
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
        [field({ key: "key", value: "waiting" }), field({ key: "objectives", kind: "SECTIONS", template: OBJECTIVE, sections: [] })],
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
      <RepeatableCards entry={entry} value={sectionsFromEntry(entry)} disabled={false}
        roles={undefined} channels={undefined} onChange={() => {}} />,
    )
    expect(screen.getByText("foothold")).toBeTruthy()
    expect(screen.getByText("waiting")).toBeTruthy()
    expect(screen.getByText("logs")).toBeTruthy()
    expect(screen.getByDisplayValue("SPRUCE_LOG")).toBeTruthy()
  })

  it("edits an item of an objective as part of the whole track", () => {
    const entry = track()
    const onChange = vi.fn()
    render(
      <RepeatableCards entry={entry} value={sectionsFromEntry(entry)} disabled={false}
        roles={undefined} channels={undefined} onChange={onChange} />,
    )
    fireEvent.change(screen.getByDisplayValue("SPRUCE_LOG"), { target: { value: "BIRCH_LOG" } })
    expect(onChange).toHaveBeenCalledWith([
      { key: "foothold", objectives: [{ key: "logs", target: "64", items: ["OAK_LOG", "BIRCH_LOG"] }] },
      { key: "waiting", objectives: [] },
    ])
  })

  it("adds an objective to the one milestone it was added to", () => {
    const entry = track()
    const onChange = vi.fn()
    render(
      <RepeatableCards entry={entry} value={sectionsFromEntry(entry)} disabled={false}
        roles={undefined} channels={undefined} onChange={onChange} />,
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
      <RepeatableCards entry={entry} value={sectionsFromEntry(entry)} disabled={false}
        roles={undefined} channels={undefined} onChange={() => {}} />,
    )
    expect(container.querySelectorAll('[data-slot="card"] [data-slot="card"]').length).toBe(0)
  })
})
