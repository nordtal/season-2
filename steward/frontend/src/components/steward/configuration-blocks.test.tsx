import { cleanup, fireEvent, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { ConfigEntry } from "@/lib/api"
import { resetDrafts } from "@/lib/drafts"
import {
  GUILD_UNAVAILABLE,
  Settings,
  asInput,
  backend,
  colourEntry,
  draw,
  entry,
  fieldFor,
  isBodyWithChanges,
  json,
  ladder,
  location,
  nonNull,
  open,
  requestBody,
  visibleText,
} from "@/components/steward/configuration.fixtures"

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  resetDrafts()
})

/** One language card's two values. */
function section(tag: string, channel: string): ConfigEntry[] {
  return [
    entry({ path: "tag", key: "tag", value: tag }),
    entry({ path: "contribution-channel", key: "contribution-channel", value: channel }),
  ]
}

/** Repeatable cards through the real form: `SECTIONS` draws `RepeatableCards`, and only Save writes. */
describe("repeatable cards for a SECTIONS entry", () => {
  const file = "discord-bot/access"
  const TEMPLATE: ConfigEntry[] = [
    entry({ path: "tag", key: "tag", label: "Tag" }),
    entry({
      path: "contribution-channel",
      key: "contribution-channel",
      label: "Contribution channel",
      refers: { to: "DISCORD_CHANNEL", optional: false },
    }),
  ]

  function languages(sections: ConfigEntry[][]): ConfigEntry {
    return entry({
      path: "languages",
      key: "languages",
      label: "Languages",
      kind: "SECTIONS",
      editable: true,
      template: TEMPLATE,
      sections,
    })
  }

  it("draws one card per section and adds a blank one on request", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "access", service: "discord-bot" }),
          revision: "r1",
          restartRequired: false,
          entries: [languages([section("en", "")])],
        },
      }),
    )
    draw(<Settings service="discord-bot" />)
    await open("Access")

    await screen.findByDisplayValue("en")
    fireEvent.click(screen.getByRole("button", { name: /Add entry/ }))

    const tags = screen.getAllByLabelText("Tag").map(asInput)
    expect(tags.map((input) => input.value)).toEqual(["en", ""])
  })

  it("titles a language card by its name, not its tag", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "access", service: "discord-bot" }),
          revision: "r1",
          restartRequired: false,
          entries: [languages([section("en", ""), section("de", "")])],
        },
      }),
    )
    draw(<Settings service="discord-bot" />)
    await open("Access")

    await screen.findByDisplayValue("en")

    screen.getByText("English")
    screen.getByText("Deutsch")
    expect(screen.queryByText("Entry 1")).toBeNull()
    expect(screen.queryByText("en", { selector: "span" })).toBeNull()

    /** A blank new card falls back to its index for a title. */
    fireEvent.click(screen.getByRole("button", { name: /Add entry/ }))
    screen.getByText("Entry 3")
  })

  it("gives a channel field inside a card the reference picker, not a plain text box", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "access", service: "discord-bot" }),
          revision: "r1",
          restartRequired: false,
          entries: [languages([section("en", "")])],
        },
      }),
    )
    draw(<Settings service="discord-bot" />)
    await open("Access")

    /** Without a bot token the picker degrades to a text input under the guild's reason. */
    expect(await screen.findByText("no bot token in this test")).not.toBeNull()
  })

  it("removes a card from the draft only, and writes it on Save - not on the click", async () => {
    let putChanges: unknown
    const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>(async (url, init) => {
      if (url === "/api/setting-groups") {
        return json([
          { service: "discord-bot", name: "access", path: file, label: "", live: true, readable: true, writable: true },
        ])
      }
      if (url === "/api/discord/channels") return json(GUILD_UNAVAILABLE)
      if (url === `/api/setting-groups/${file}` && init?.method === "PUT") {
        const parsed: unknown = JSON.parse(requestBody(init.body))
        if (!isBodyWithChanges(parsed)) throw new Error("the save did not carry changes")
        putChanges = parsed.changes
        return json({
          ...location({ path: file, name: "access", service: "discord-bot" }),
          revision: "r2",
          restartRequired: false,
          entries: [languages([section("en", "")])],
        })
      }
      if (url === `/api/setting-groups/${file}`) {
        return json({
          ...location({ path: file, name: "access", service: "discord-bot" }),
          revision: "r1",
          restartRequired: false,
          entries: [languages([section("en", ""), section("de", "")])],
        })
      }
      throw new Error(`the form asked for ${url}, which this test did not expect`)
    })
    vi.stubGlobal("fetch", fetchMock)

    draw(<Settings service="discord-bot" />)
    await open("Access")
    await screen.findByDisplayValue("de")

    fireEvent.click(screen.getByRole("button", { name: "Remove entry 2" }))
    // Removing asks first; the click only arms the confirmation.
    fireEvent.click(screen.getByRole("button", { name: "Remove it" }))

    // The card is gone from the draft and the count says so, but nothing has been written yet.
    expect(screen.queryByDisplayValue("de")).toBeNull()
    screen.getByRole("button", { name: "Save 1" })
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(false)

    fireEvent.click(screen.getByRole("button", { name: "Save 1" }))

    await waitFor(() => expect(fetchMock.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(true))
    expect(putChanges).toEqual({ languages: [{ tag: "en", "contribution-channel": "" }] })
  })
})

/** The five tones of `colours.yml` through the real form, drawn as one run. */
describe("a file of colours, side by side", () => {
  const file = "smp/colours.yml"

  it("draws every tone of one file as one row, not five stacked fields", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "colours.yml", service: "smp" }),
          revision: "r1",
          restartRequired: false,
          entries: [
            colourEntry("good", "#8ba888"),
            colourEntry("bad", "#a8888b"),
            colourEntry("warn", "#b08a4a"),
            colourEntry("neutral", "#c9c9c9"),
            colourEntry("muted", "#aaaaaa"),
          ],
        },
      }),
    )
    draw(<Settings service="smp" />)
    await open("Colours")

    const swatches = await screen.findAllByLabelText("Pick a colour")
    expect(swatches).toHaveLength(5)

    /** Every swatch shares one row ancestor, the container `EntryList` builds for a run. */
    const rows = new Set(swatches.map((swatch) => swatch.closest(".flex-wrap")))
    expect(rows.size).toBe(1)
    expect(rows.has(null)).toBe(false)
  })

  it("still offers the picker for a colour that is not part of any row", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "colours.yml", service: "smp" }),
          revision: "r1",
          restartRequired: false,
          /** A single colour is not grouped, yet still gets the picker. */
          entries: [colourEntry("good", "#8ba888"), entry({ path: "label", key: "label", value: "smp" })],
        },
      }),
    )
    draw(<Settings service="smp" />)
    await open("Colours")

    expect(await screen.findByLabelText("Pick a colour")).not.toBeNull()
  })

  it("leaves an ordinary text field alone - no picker, no swatch", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "colours.yml", service: "smp" }),
          revision: "r1",
          restartRequired: false,
          entries: [entry({ path: "label", key: "label", label: "Label", value: "smp" })],
        },
      }),
    )
    draw(<Settings service="smp" />)
    await open("Colours")

    await screen.findByDisplayValue("smp")
    expect(screen.queryByLabelText("Pick a colour")).toBeNull()
  })
})

/** `prestige.yml`'s two blocks through the real form, as one row per tier with its hour and colour. */
describe("two blocks that share their keys, as one row per key", () => {
  const file = "smp/prestige.yml"

  function withLadder() {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "prestige.yml", service: "smp" }),
          revision: "r1",
          restartRequired: false,
          entries: ladder(["0", "2", "5"], ["#5fbfae", "#5ea9d6", "#6f93e0"]),
        },
      }),
    )
  }

  it("puts a tier's hour and its colour in the same row", async () => {
    withLadder()
    const { container } = draw(<Settings service="smp" />)
    await open("Prestige")

    await screen.findByDisplayValue("2")
    const hour = fieldFor(container, "hours.tier-02")
    const colour = fieldFor(container, "colours.tier-02")
    const row = hour.closest("li")

    expect(row).not.toBeNull()
    /** The same `<li>`, which is what one row per tier means in the DOM. */
    expect(colour.closest("li")).toBe(row)
    expect(row?.textContent).toContain("tier-02")
  })

  it("names the tier once, and each column once for the whole block", async () => {
    withLadder()
    const { container } = draw(<Settings service="smp" />)
    await open("Prestige")

    await screen.findByDisplayValue("2")
    const row = nonNull(fieldFor(container, "hours.tier-02").closest<HTMLElement>("li"), "the row")
    /** The tier is named once, and the halves carry only `sr-only` labels, removed here first. */
    expect(visibleText(row).match(/tier-02/g) ?? []).toHaveLength(1)
    const block = nonNull(row.closest<HTMLElement>("ul"), "the block")
    expect(visibleText(block).match(/hours/g) ?? []).toHaveLength(1)
    expect(visibleText(block).match(/colours/g) ?? []).toHaveLength(1)
  })

  /** The label is hidden for the eye but kept as the field's accessible name. */
  it("keeps each half's label for a screen reader", async () => {
    withLadder()
    draw(<Settings service="smp" />)
    await open("Prestige")

    await screen.findByDisplayValue("2")
    expect(screen.getByLabelText("hours tier-02")).toBeTruthy()
    expect(screen.getByLabelText("colours tier-02")).toBeTruthy()
  })

  it("keeps the colour picker, because a paired field is still the field it was", async () => {
    withLadder()
    draw(<Settings service="smp" />)
    await open("Prestige")

    // Three tiers, plus `admin` standing outside the pair as an ordinary colour field.
    expect(await screen.findAllByLabelText("Pick a colour")).toHaveLength(4)
  })

  it("writes both halves of a row under their own real keys", async () => {
    withLadder()
    const fetchMock = vi.mocked(global.fetch)
    const { container } = draw(<Settings service="smp" />)
    await open("Prestige")

    await screen.findByDisplayValue("2")
    fireEvent.change(fieldFor(container, "hours.tier-02"), { target: { value: "3" } })
    fireEvent.change(fieldFor(container, "colours.tier-02"), { target: { value: "#112233" } })
    fireEvent.click(screen.getByRole("button", { name: /save/i }))

    await waitFor(() => expect(fetchMock.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(true))
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === "PUT")
    const body = requestBody(put?.[1]?.body)
    const parsed: unknown = JSON.parse(body)
    if (!isBodyWithChanges(parsed)) throw new Error("the save did not carry changes")
    expect(parsed.changes).toEqual({
      /** The display label was overridden and the path was not, so search and save hit the same key. */
      "hours.tier-02": "3",
      "colours.tier-02": "#112233",
    })
  })

  it("leaves two blocks that do not line up as two ordinary stacks", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "prestige.yml", service: "smp" }),
          revision: "r1",
          restartRequired: false,
          entries: [
            entry({ path: "hours", key: "hours", label: "hours", kind: "MAP", editable: false }),
            entry({ path: "hours.tier-01", key: "tier-01", label: "tier-01", value: "0" }),
            entry({ path: "hours.tier-02", key: "tier-02", label: "tier-02", value: "2" }),
            entry({ path: "colours", key: "colours", label: "colours", kind: "MAP", editable: false }),
            entry({ path: "colours.tier-01", key: "tier-01", label: "tier-01", value: "#5fbfae" }),
          ],
        },
      }),
    )
    const { container } = draw(<Settings service="smp" />)
    await open("Prestige")

    /** Nothing is paired, and all three values are still drawn as separate fields. */
    await screen.findByDisplayValue("2")
    expect(fieldFor(container, "hours.tier-02").closest("li")).toBeNull()
    expect(fieldFor(container, "hours.tier-01").value).toBe("0")
    expect(fieldFor(container, "colours.tier-01").value).toBe("#5fbfae")
  })
})
