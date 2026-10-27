import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react"
import { toast } from "sonner"
import { afterEach, describe, expect, it, vi } from "vitest"

import { resetDrafts } from "@/lib/drafts"
import { setPendingTextJump } from "@/lib/settings-search"
import type { MessageFallback } from "@/lib/api"
import { asButton, asTextArea } from "@/lib/test-elements"
import { changesOf, words } from "@/lib/query-fixtures"
import {
  PLACES,
  backend,
  documents,
  draw,
  listing,
  open,
  openItem,
  Page,
  source,
  text,
  view,
} from "@/pages/translations.fixtures"

vi.mock("sonner", () => ({
  toast: {
    success: vi.fn<(message: string) => void>(),
    warning: vi.fn<(message: string) => void>(),
    info: vi.fn<(message: string) => void>(),
    error: vi.fn<(message: string) => void>(),
  },
}))

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
  resetDrafts()
})

describe("the groups", () => {
  const fixture = listing([
    text({ key: "welcome", name: "Welcome" }),
    text({ key: "dm.granted", bundle: "admin", name: "Granted", shown: ["DISCORD_MESSAGE"] }),
    text({ key: "page.title", bundle: "steward", name: "Page title", shown: ["STEWARD"] }),
    text({ key: "values.yes", bundle: "values", name: "Yes", shown: ["CHAT", "DISCORD_MESSAGE"] }),
  ])

  it("lists the areas where texts appear, the values apart, and an opened area's texts alone", async () => {
    vi.stubGlobal("fetch", backend(fixture))
    draw(<Page />)

    const areas = await screen.findByRole("list", { name: "Areas" })
    expect(
      within(areas)
        .getAllByRole("button")
        .map((area) => area.textContent),
    ).toEqual(["Discord1", "In game1", "Steward and admin1", "Building blocks1"])
    expect(screen.queryByRole("button", { name: "Welcome" })).toBeNull()

    await open(/^Discord/)
    screen.getByRole("button", { name: "Granted" })
    expect(screen.queryByRole("button", { name: "Welcome" })).toBeNull()
    fireEvent.click(screen.getByRole("button", { name: "Areas" }))
    await screen.findByRole("list", { name: "Areas" })
  })

  it("lists what the server's search finds, in every group at once", async () => {
    const asked: string[] = []
    vi.stubGlobal(
      "fetch",
      backend(fixture, undefined, {
        search: (query) => {
          asked.push(query)
          return [
            { kind: "TEXT", id: "steward/page.title", language: "en", field: "TEXT", exact: false, text: "x" },
            { kind: "TEXT", id: "values/values.yes", language: "en", field: "NAME", exact: false, text: "x" },
          ]
        },
      }),
    )
    draw(<Page />)

    fireEvent.change(await screen.findByRole("searchbox", { name: "Search the texts" }), {
      target: { value: "titel" },
    })

    expect(await screen.findByRole("button", { name: "Page title" })).toBeTruthy()
    expect(screen.getByRole("button", { name: "Yes" })).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Granted" })).toBeNull()
    expect(asked).toContain("titel")
  })

  it("puts the search over the list, above the service filter and the language", async () => {
    vi.stubGlobal("fetch", backend(listing(fixture.texts, { languages: ["en", "de"] })))
    draw(<Page />)

    const search = await screen.findByRole("searchbox", { name: "Search the texts" })
    const filter = screen.getByRole("combobox", { name: "Service" })
    const language = screen.getByRole("tablist", { name: "Language" })
    expect(screen.getByRole("heading", { level: 1 }).closest("header")!.contains(search)).toBe(false)
    expect(search.compareDocumentPosition(filter) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(filter.compareDocumentPosition(language) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it("says so when no jar ships a text", async () => {
    vi.stubGlobal("fetch", backend(listing([])))
    draw(<Page />)

    expect(await screen.findByText("No texts.")).toBeTruthy()
  })
})

describe("the service filter", () => {
  const fixture = listing(
    [
      text({ key: "welcome", name: "Welcome", texts: { en: ["<good>Welcome</good>"] }, format: "MINIMESSAGE" }),
      text(
        { key: "dm.granted", bundle: "admin", name: "Granted", shown: ["DISCORD_MESSAGE"] },
        { path: "discord-bot/discord-bot", services: ["discord-bot"] },
      ),
    ],
    { colours: { smp: { good: "#123456" } } },
  )

  it("lists only the texts the chosen service shows", async () => {
    vi.stubGlobal("fetch", backend(fixture))
    draw(<Page />)
    await screen.findByRole("button", { name: /^In game/ })

    fireEvent.keyDown(screen.getByRole("combobox", { name: "Service" }), { key: "Enter" })
    fireEvent.click(await screen.findByRole("option", { name: "Discord Bot" }))

    await open(/^Discord/)
    expect(await screen.findByRole("button", { name: "Granted" })).toBeTruthy()
    fireEvent.click(screen.getByRole("button", { name: "Areas" }))
    await screen.findByRole("list", { name: "Areas" })
    expect(screen.queryByRole("button", { name: /^In game/ })).toBeNull()
  })

  it("offers every service whose texts or documents the page lists", async () => {
    const card = view({ name: "steward-card", title: "Steward card" }, { parts: [] }, { services: ["steward"] })
    vi.stubGlobal("fetch", backend(fixture, undefined, { documents: documents([card]) }))
    draw(<Page />)
    await screen.findByRole("button", { name: /^In game/ })

    fireEvent.keyDown(screen.getByRole("combobox", { name: "Service" }), { key: "Enter" })

    const offered = (await screen.findAllByRole("option")).map((option) => option.textContent)
    expect(offered).toEqual(["All services", "Discord Bot", "SMP", "Steward"])
  })

  it("draws a tone in the colour the filtered service's settings give it", async () => {
    vi.stubGlobal("fetch", backend(fixture))
    draw(<Page service="smp" />)

    const item = await openItem("Welcome")
    await waitFor(() =>
      expect(
        within(item)
          .getAllByText("Welcome")
          .map((span) => span.style.color),
      ).toContain("rgb(18, 52, 86)"),
    )
  })

  it("draws it in the network's colour without a filter", async () => {
    vi.stubGlobal("fetch", backend(fixture))
    draw(<Page />)

    const item = await openItem("Welcome")
    await waitFor(() =>
      expect(
        within(item)
          .getAllByText("Welcome")
          .map((span) => span.style.color),
      ).toContain("rgb(139, 168, 136)"),
    )
  })
})

describe("the pills", () => {
  it("name every place a text appears, and a whole surface it fills as one", async () => {
    const everyGamePlace = Object.keys(PLACES).filter((place) => PLACES[place] === "GAME")
    vi.stubGlobal(
      "fetch",
      backend(
        listing([
          text({ key: "a", name: "First", shown: ["GUI", "DISCORD_MESSAGE", "STEWARD"] }),
          text({ key: "b", name: "Second", shown: [...everyGamePlace, "PUSH"] }),
        ]),
      ),
    )
    draw(<Page />)

    const first = await openItem("First")
    expect(within(first).getByLabelText("Shown in").textContent).toBe("MenuDiscord messageSteward")
    const second = await openItem("Second")
    expect(within(second).getByLabelText("Shown in").textContent).toBe("In gameNotification")
  })

  it("sit under the open text's name, beside its trail and above its preview", async () => {
    twoKeys()
    draw(<Page />)

    const item = await openItem("First")
    const line = within(item).getByLabelText("Shown in").parentElement!
    expect(line.textContent).toContain("In game")
    expect(line.textContent).not.toContain("Hello")
    const name = within(item).getByRole("heading", { level: 2, name: "First" })
    expect(name.compareDocumentPosition(line) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})

describe("the languages", () => {
  it("offers the network's languages, and keeps a bundle that ships English only to English", async () => {
    vi.stubGlobal(
      "fetch",
      backend(
        listing(
          [
            text({ key: "title", bundle: "steward", name: "Page title", texts: { en: ["Page"] } }),
            text({ key: "welcome", name: "Welcome", texts: { en: ["Welcome"], de: ["packaged-de-text"] } }),
          ],
          { languages: ["en", "de", "nl"] },
        ),
      ),
    )
    draw(<Page />)

    await openItem("Page title")
    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual(["EN", "DE", "NL"])
    fireEvent.mouseDown(screen.getByRole("tab", { name: /DE/ }))
    expect((await screen.findByRole("textbox", { name: "Page title" })).textContent).toBe("Page")

    await openItem("Welcome")
    expect((await screen.findByRole("textbox", { name: "Welcome" })).textContent).toBe("packaged-de-text")
  })
})

describe("the en/de toggle", () => {
  const fixture = listing([
    text({
      texts: { en: ["Welcome"], de: ["packaged-de-text"] },
      overrides: { de: ["override-de-text"] },
      key: "welcome",
    }),
  ])

  it("shows English packaged text by default", async () => {
    vi.stubGlobal("fetch", backend(fixture))
    draw(<Page />)
    await openItem("Welcome")

    expect((await screen.findByRole("textbox", { name: "welcome" })).textContent).toBe("Welcome")
  })

  it("switches to the override once German is selected, rather than the packaged text", async () => {
    vi.stubGlobal("fetch", backend(fixture))
    draw(<Page />)
    await openItem("Welcome")
    await source("welcome")

    fireEvent.mouseDown(screen.getByRole("tab", { name: /DE/ }))

    await screen.findByDisplayValue("override-de-text")
    expect(screen.queryByDisplayValue("packaged-de-text")).toBeNull()
  })
})

describe("saving", () => {
  it("warns, but still saves, when the edited text drops a placeholder the packaged text had", async () => {
    vi.stubGlobal(
      "fetch",
      backend(listing([text({ texts: { en: ["Hello {sender}"] }, key: "greeting" })]), () => ({
        texts: listing([
          text({ texts: { en: ["Hello {sender}"] }, overrides: { en: ["Hello there"] }, key: "greeting" }),
        ]),
        warnings: [
          {
            bundle: "smp",
            key: "greeting",
            language: "en",
            text: { key: "check.value.unshown", args: { role: { kind: "text", value: "sender" } } },
          },
        ],
      })),
    )
    draw(<Page />)
    await openItem("Greeting")
    fireEvent.change(await source("greeting"), { target: { value: "Hello there" } })
    fireEvent.click(screen.getByRole("button", { name: /^Save/ }))

    expect(await screen.findByText(/the text never shows sender, which the message is given/)).toBeTruthy()
    expect(await screen.findByDisplayValue("Hello there")).toBeTruthy()
  })

  /** The save's own answer says whether the text is in force. The toast says which of two. */
  it.each([
    ["in force", "APPLIED", toast.success],
    ["in force after a restart", "RESTART_REQUIRED", toast.info],
  ] as const)("says a saved text is %s", async (_what, status, shown) => {
    const message = `the service said ${status}`
    vi.stubGlobal(
      "fetch",
      backend(listing([text({ texts: { en: ["Welcome"] }, key: "welcome" })]), () => ({
        texts: listing([text({ texts: { en: ["Welcome"] }, overrides: { en: ["Howdy"] }, key: "welcome" })]),
        reload: { status, message: words(message) },
      })),
    )
    draw(<Page />)
    await openItem("Welcome")
    fireEvent.change(await source("welcome"), { target: { value: "Howdy" } })
    fireEvent.click(screen.getByRole("button", { name: /^Save/ }))

    await waitFor(() => expect(shown).toHaveBeenCalledWith("One text saved.", { description: message }))
  })

  it("resets a key by removing the override, not by copying English into it", async () => {
    vi.stubGlobal(
      "fetch",
      backend(listing([text({ texts: { en: ["Welcome"] }, overrides: { en: ["Howdy"] }, key: "welcome" })]), (body) => {
        expect(changesOf(body)).toEqual({ smp: { welcome: { en: null } } })
        return { texts: listing([text({ texts: { en: ["Welcome"] }, key: "welcome" })]) }
      }),
    )
    draw(<Page />)
    await openItem("Welcome")
    expect((await source("welcome")).value).toBe("Howdy")

    fireEvent.click(screen.getByRole("button", { name: /Reset/ }))
    await screen.findByDisplayValue("Welcome")
    fireEvent.click(screen.getByRole("button", { name: /^Save/ }))

    /**
     * The reset draft shows the packaged "Welcome" too, so only the saved draft's Save button going proves the round trip.
     */
    await waitFor(() => expect(asButton(screen.getByRole("button", { name: /^Save/ })).disabled).toBe(true))
    expect(await screen.findByDisplayValue("Welcome")).toBeTruthy()
    expect(screen.queryByRole("button", { name: /Reset/ })).toBeNull()
    expect(screen.queryByText("overridden")).toBeNull()
  })

  it("saves the open text alone and keeps the draft of the other", async () => {
    const bodies: unknown[] = []
    vi.stubGlobal(
      "fetch",
      backend(
        listing([
          text({ texts: { en: ["one"], de: ["eins"] }, key: "a", name: "First" }),
          text({ texts: { en: ["two"], de: ["zwei"] }, key: "a", bundle: "paper-common", name: "Second" }),
        ]),
        (body) => {
          bodies.push(body)
          return {}
        },
      ),
    )
    draw(<Page />)

    await openItem("First")
    fireEvent.change(await source("First"), { target: { value: "ONE" } })
    await openItem("Second")
    fireEvent.mouseDown(await screen.findByRole("tab", { name: /DE/ }))
    fireEvent.change(await source("Second"), { target: { value: "ZWEI" } })
    fireEvent.click(screen.getByRole("button", { name: "Save" }))

    await waitFor(() => expect(bodies).toHaveLength(1))
    expect(bodies[0]).toEqual({ changes: { "paper-common": { a: { de: ["ZWEI"] } } } })
    expect(within(screen.getByRole("button", { name: "First" })).getByLabelText("Changed")).toBeTruthy()
  })
})

describe("the sections of an area", () => {
  it("are named after the spec's topics, with the branches below in each text's trail and no keys", async () => {
    vi.stubGlobal(
      "fetch",
      backend(
        listing([
          text({
            texts: { en: ["Soon"] },
            key: "grave.decay.warning",
            name: "Decay warning",
            section: ["Graves", "Decay"],
          }),
          text({ texts: { en: ["Welcome"] }, key: "welcome", name: "Welcome" }),
        ]),
      ),
    )
    draw(<Page />)

    await open(/^In game/)
    const graves = await screen.findByRole("region", { name: "Graves" })
    const row = within(graves).getByRole("button", { name: "Decay warning" })
    expect(row.textContent).toContain("Decay")
    expect(screen.getByRole("region", { name: "In game" }).textContent).toContain("Welcome")
    expect(screen.queryByText("grave.decay.warning")).toBeNull()
  })
})

function twoKeys() {
  vi.stubGlobal(
    "fetch",
    backend(
      listing([
        text({
          texts: { en: ["<gray>Hello <white>{player}</white></gray>"] },
          key: "a",
          name: "First",
          args: [{ name: "player", kind: "text", global: false, action: false }],
        }),
        text({ texts: { en: ["two"] }, key: "b", name: "Second" }),
      ]),
    ),
  )
}

describe("one text open at a time", () => {
  it("lists each text as its name and its words, with no field until it is opened", async () => {
    twoKeys()
    draw(<Page />)

    await open(/^In game/)
    const row = await screen.findByRole("button", { name: "First" })
    expect(row.textContent).toContain("Hello")
    expect(row.textContent).toContain("player")
    expect(row.textContent).not.toContain("<gray>")
    expect(screen.queryByRole("textbox")).toBeNull()
  })

  it("opens a text under its name beside the list, and goes back to its area's list", async () => {
    twoKeys()
    draw(<Page />)

    const item = await openItem("First")
    within(item).getByRole("heading", { level: 2, name: "First" })
    expect(within(item).getByRole("textbox", { name: "First" })).toBeTruthy()
    expect(screen.getByRole("button", { name: "First" }).getAttribute("aria-current")).toBe("true")
    screen.getByRole("button", { name: "Second" })

    fireEvent.click(within(item).getByRole("button", { name: "Back" }))
    await waitFor(() => expect(screen.queryByRole("article")).toBeNull())
    screen.getByRole("button", { name: "First" })
  })

  it("keeps the draft of a text when another is opened", async () => {
    twoKeys()
    draw(<Page />)

    await openItem("First")
    const first = await source("First")
    expect(first.value).toBe("<gray>Hello <white>{player}</white></gray>")
    fireEvent.change(first, { target: { value: "Hi {player}" } })
    await openItem("Second")

    expect((await screen.findByRole("textbox", { name: "Second" })).textContent).toBe("two")
    expect(screen.queryByRole("textbox", { name: "First" })).toBeNull()
    expect(within(screen.getByRole("button", { name: "First" })).getByLabelText("Changed")).toBeTruthy()
    expect(asButton(screen.getByRole("button", { name: "Save" })).disabled).toBe(true)
  })

  it("drops the open text's draft alone with Discard, which shows its stored text again", async () => {
    twoKeys()
    draw(<Page />)

    await openItem("First")
    fireEvent.change(await source("First"), { target: { value: "Hi {player}" } })
    await openItem("Second")
    fireEvent.change(await source("Second"), { target: { value: "zwei" } })

    fireEvent.click(screen.getByRole("button", { name: "Discard" }))

    expect(asTextArea(screen.getByRole("textbox", { name: "Second" })).value).toBe("two")
    expect(asButton(screen.getByRole("button", { name: "Save" })).disabled).toBe(true)
    const first = screen.getByRole("button", { name: "First" })
    expect(first.textContent).toContain("Hi")
    expect(within(first).getByLabelText("Changed")).toBeTruthy()
  })
})

describe("placeholders", () => {
  const greeting = listing([
    text({
      texts: { en: ["Hello {player}"] },
      key: "greeting",
      name: "Greeting",
      args: [{ name: "player", kind: "text", global: false, action: false }],
    }),
  ])

  it("shows what the validator says of the typed text, and leaves the refusal to the save", async () => {
    vi.stubGlobal(
      "fetch",
      backend(greeting, undefined, {
        check: (typed) =>
          typed.includes("{palyer}")
            ? [
                {
                  error: true,
                  text: {
                    key: "check.value.unknown",
                    args: {
                      name: { kind: "text", value: "palyer" },
                      offered: { kind: "text", value: "player" },
                    },
                  },
                },
              ]
            : [],
      }),
    )
    draw(<Page />)
    await openItem("Greeting")

    fireEvent.change(await source("Greeting"), { target: { value: "Hello {palyer}" } })

    await screen.findByText("this message offers player, not {palyer}")
    expect(asButton(screen.getByRole("button", { name: "Save" })).disabled).toBe(false)
  })

  it("inserts a declared placeholder from the menu where the caret is", async () => {
    vi.stubGlobal("fetch", backend(greeting))
    draw(<Page />)
    await openItem("Greeting")
    const field = await source("Greeting")
    field.setSelectionRange(0, 0)

    fireEvent.click(screen.getByRole("button", { name: "Insert a value" }))
    fireEvent.click(await screen.findByRole("button", { name: /^player/ }))

    expect(await screen.findByDisplayValue("{player}Hello {player}")).toBeTruthy()
  })
})

describe("a fallen-back override", () => {
  const fallback: MessageFallback = {
    bundle: "smp",
    key: "welcome",
    language: "en",
    reason: "STALE",
    override: ["Howdy"],
    original: ["Welcome"],
    packaged: ["Welcome aboard"],
    problems: [],
  }

  it("shows what it was written over and what the jar has now, and takes it over in one save", async () => {
    const bodies: unknown[] = []
    vi.stubGlobal(
      "fetch",
      backend(
        listing([text({ texts: { en: ["Welcome aboard"] }, overrides: { en: ["Howdy"] }, key: "welcome" })]),
        (body) => {
          bodies.push(body)
          return {}
        },
        { fallbacks: [fallback] },
      ),
    )
    draw(<Page />)
    await openItem("Welcome")

    await screen.findByText("Fallen back")
    expect(screen.getAllByRole("definition").map((definition) => definition.textContent)).toEqual([
      "Welcome",
      "Welcome aboard",
      "Howdy",
    ])
    fireEvent.click(screen.getByRole("button", { name: "Take over" }))

    await waitFor(() => expect(bodies).toHaveLength(1))
    expect(bodies[0]).toEqual({ changes: { smp: { welcome: { en: ["Howdy"] } } } })
  })
})

describe("the two views", () => {
  it("opens a text it cannot read as it is written, and offers no view of it as it looks", async () => {
    vi.stubGlobal("fetch", backend(listing([text({ texts: { en: ["Hello {name"] }, key: "broken", name: "Broken" })])))
    draw(<Page />)
    await openItem("Broken")

    expect(asTextArea(await screen.findByRole("textbox", { name: "Broken" })).value).toBe("Hello {name")
    expect(asButton(screen.getByRole("button", { name: "As it looks" })).disabled).toBe(true)
  })
})

describe("a jump from the command palette", () => {
  it("opens the text and switches it to the language of the hit", async () => {
    vi.stubGlobal(
      "fetch",
      backend(
        listing([
          text({ texts: { en: ["Welcome"], de: ["packaged-de-welcome"] }, key: "welcome", name: "Welcome" }),
          text({ key: "dm.granted", bundle: "admin", name: "Granted", shown: ["DISCORD_MESSAGE"] }),
        ]),
      ),
    )
    draw(<Page />)
    await screen.findByRole("button", { name: /^In game/ })

    setPendingTextJump({ id: "smp/welcome", language: "de" })

    await waitFor(() =>
      expect(screen.getByRole("textbox", { name: "Welcome" }).textContent).toBe("packaged-de-welcome"),
    )
  })
})

const dimmedByAnyTool = (group: Element) => [...group.classList].some((name) => name.startsWith("has-disabled:"))

describe("the editor's look", () => {
  it("is not dimmed by a tool that waits for a selection", async () => {
    vi.stubGlobal("fetch", backend(listing([text({ texts: { en: ["Hello"] }, key: "a", name: "First" })])))
    draw(<Page />)
    await openItem("First")

    const group = (await screen.findByRole("textbox", { name: "First" })).closest("[data-slot=input-group]")!
    expect(group.querySelector("button:disabled")).not.toBeNull()
    expect(dimmedByAnyTool(group)).toBe(false)
  })
})

const days = () =>
  vi.stubGlobal(
    "fetch",
    backend(
      listing([
        text({
          texts: { en: ["{n, plural, one {# day} other {# days}}"] },
          key: "days",
          name: "Days",
          args: [{ name: "n", kind: "number", global: false, action: false }],
        }),
      ]),
    ),
  )

describe("a plural as its cases", () => {
  it("opens one case at a time, each tab named with the numbers it stands for", async () => {
    days()
    draw(<Page />)
    await openItem("Days")

    const cases = await screen.findByRole("region", { name: "By the number n" })
    expect(
      within(cases)
        .getAllByRole("tab")
        .map((tab) => tab.textContent),
    ).toEqual(["one1", "other0, 2, 3"])
    expect(within(cases).getByRole("textbox", { name: "Days one" }).textContent).toBe("# day")
    expect(within(cases).queryByRole("textbox", { name: "Days other" })).toBeNull()

    fireEvent.mouseDown(within(cases).getByRole("tab", { name: /^other/ }))
    expect((await within(cases).findByRole("textbox", { name: "Days other" })).textContent).toBe("# days")
  })

  it("adds an exact number before other and removes the open case, and the source shows the syntax they make", async () => {
    days()
    draw(<Page />)
    await openItem("Days")

    const cases = await screen.findByRole("region", { name: "By the number n" })
    fireEvent.click(within(cases).getByRole("button", { name: "Remove this case" }))
    fireEvent.click(within(cases).getByRole("button", { name: "Add a case" }))
    fireEvent.change(await screen.findByRole("textbox", { name: "Exact number" }), { target: { value: "0" } })
    fireEvent.submit(screen.getByRole("textbox", { name: "Exact number" }))

    expect(await within(cases).findByRole("textbox", { name: "Days =0" })).toBeTruthy()
    expect((await source("Days")).value).toBe("{n, plural, =0 {# days} other {# days}}")
  })

  it("draws its cases in the tone around the plural", async () => {
    vi.stubGlobal(
      "fetch",
      backend(
        listing(
          [
            text({
              texts: { en: ["<good>{n, plural, one {# day} other {# days}}</good>"] },
              key: "days",
              name: "Days",
              format: "MINIMESSAGE",
              args: [{ name: "n", kind: "number", global: false, action: false }],
            }),
          ],
          { colours: { smp: { good: "#123456" } } },
        ),
      ),
    )
    draw(<Page service="smp" />)
    await openItem("Days")

    const one = await screen.findByRole("textbox", { name: "Days one" })
    expect(within(one).getByText("day").style.color).toBe("rgb(18, 52, 86)")
  })
})
