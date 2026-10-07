import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react"
import { toast } from "sonner"
import { afterEach, describe, expect, it, vi } from "vitest"

import { resetDrafts } from "@/lib/drafts"
import { setPendingTextJump } from "@/lib/settings-search"
import type { MessageFallback } from "@/lib/api"
import { asButton, asTextArea } from "@/lib/test-elements"
import { changesOf, words } from "@/lib/query-fixtures"
import { PLACES, backend, draw, listing, open, openKey, Page, source, text } from "@/pages/texts.fixtures"

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
    text({ key: "dm.granted", bundle: "access", name: "Granted", shown: ["DISCORD_MESSAGE"] }),
    text({ key: "page.title", bundle: "steward", name: "Page title", shown: ["STEWARD"] }),
    text({ key: "values.yes", bundle: "values", name: "Yes", shown: ["CHAT", "DISCORD_MESSAGE"] }),
  ])

  it("lists every text under where it appears, the values apart, each group closed", async () => {
    vi.stubGlobal("fetch", backend(fixture))
    draw(<Page />)

    for (const group of ["In game", "Discord", "Steward & Admin", "Building blocks"]) {
      const branch = await screen.findByRole("button", { name: new RegExp(`^${group}`) })
      expect(branch.getAttribute("aria-expanded")).toBe("false")
    }
    expect(screen.queryByRole("button", { name: "Welcome" })).toBeNull()

    await open(/^Discord/)
    screen.getByRole("button", { name: "Granted" })
    expect(screen.queryByRole("button", { name: "Welcome" })).toBeNull()
  })

  it("finds a text by its words in every group at once", async () => {
    vi.stubGlobal("fetch", backend(fixture))
    draw(<Page />)

    fireEvent.change(await screen.findByRole("searchbox", { name: "Search the texts" }), {
      target: { value: "title" },
    })

    expect(await screen.findByRole("button", { name: "Page title" })).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Granted" })).toBeNull()
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
        { key: "dm.granted", bundle: "access", name: "Granted", shown: ["DISCORD_MESSAGE"] },
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

    expect(await screen.findByRole("button", { name: "Granted" })).toBeTruthy()
    expect(screen.queryByRole("button", { name: /^In game/ })).toBeNull()
  })

  it("draws a tone in the colour the filtered service's settings give it", async () => {
    vi.stubGlobal("fetch", backend(fixture))
    draw(<Page service="smp" />)

    const row = await screen.findByRole("button", { name: "Welcome" })
    await waitFor(() =>
      expect(
        within(row)
          .getAllByText("Welcome")
          .map((span) => span.style.color),
      ).toContain("rgb(18, 52, 86)"),
    )
  })

  it("draws it in the network's colour without a filter", async () => {
    vi.stubGlobal("fetch", backend(fixture))
    draw(<Page />)
    await open(/^In game/)

    const row = await screen.findByRole("button", { name: "Welcome" })
    await waitFor(() =>
      expect(
        within(row)
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

    const first = await screen.findByRole("button", { name: "First" })
    expect(within(first).getByLabelText("Shown in").textContent).toBe("MenuDiscord messageSteward")
    const second = screen.getByRole("button", { name: "Second" })
    expect(within(second).getByLabelText("Shown in").textContent).toBe("In gameNotification")
  })
})

describe("the languages", () => {
  it("keeps a bundle that ships English only to English, and offers the network's to every other", async () => {
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

    await openKey("Page title")
    await screen.findByRole("tab", { name: /EN/ })
    expect(screen.queryByRole("tab", { name: /DE/ })).toBeNull()

    await openKey("Welcome")
    expect(await screen.findByRole("tab", { name: /NL/ })).toBeTruthy()
    screen.getByRole("tab", { name: /DE/ })
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
    await openKey("Welcome")

    expect((await screen.findByRole("textbox", { name: "welcome" })).textContent).toBe("Welcome")
  })

  it("switches to the override once German is selected, rather than the packaged text", async () => {
    vi.stubGlobal("fetch", backend(fixture))
    draw(<Page />)
    await openKey("Welcome")
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
    await openKey("Greeting")
    fireEvent.change(await source("greeting"), { target: { value: "Hello there" } })
    fireEvent.click(screen.getByRole("button", { name: /^Save/ }))

    expect(await screen.findByText(/the text never shows sender, which the message is given/)).toBeTruthy()
    expect(await screen.findByDisplayValue("Hello there")).toBeTruthy()
  })

  /** The save's own answer says whether the text is in force; the toast says which of two. */
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
    await openKey("Welcome")
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
    await openKey("Welcome")
    expect((await source("welcome")).value).toBe("Howdy")

    fireEvent.click(screen.getByRole("button", { name: /Reset/ }))
    await screen.findByDisplayValue("Welcome")
    fireEvent.click(screen.getByRole("button", { name: /^Save/ }))

    /** The reset draft shows the packaged "Welcome" too, so only the saved draft's Save button going proves the round trip. */
    await waitFor(() => expect(screen.queryByRole("button", { name: /^Save/ })).toBeNull())
    expect(await screen.findByDisplayValue("Welcome")).toBeTruthy()
    expect(screen.queryByRole("button", { name: /Reset/ })).toBeNull()
    expect(screen.queryByText("overridden")).toBeNull()
  })

  it("sends the changes of two bundles' texts, in both languages, in one call", async () => {
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

    await openKey("First")
    fireEvent.change(await source("First"), { target: { value: "ONE" } })
    await openKey("Second")
    fireEvent.mouseDown(await screen.findByRole("tab", { name: /DE/ }))
    fireEvent.change(await source("Second"), { target: { value: "ZWEI" } })
    fireEvent.click(screen.getByRole("button", { name: "Save 2" }))

    await waitFor(() => expect(bodies).toHaveLength(1))
    expect(bodies[0]).toEqual({ changes: { smp: { a: { en: ["ONE"] } }, "paper-common": { a: { de: ["ZWEI"] } } } })
  })
})

describe("the tree of a group", () => {
  it("names its topics after the spec and shows no keys", async () => {
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

    const branch = await screen.findByRole("button", { name: /Graves.*Decay/ })
    expect(branch.getAttribute("aria-expanded")).toBe("false")
    expect(screen.queryByText("Decay warning")).toBeNull()

    fireEvent.click(branch)
    screen.getByText("Decay warning")
    expect(screen.queryByText("grave.decay.warning")).toBeNull()
  })
})

/** Two texts, so opening one can be observed closing the other. */
function twoKeys() {
  vi.stubGlobal(
    "fetch",
    backend(
      listing([
        text({
          texts: { en: ["<gray>Hello <white>{player}</white></gray>"] },
          key: "a",
          name: "First",
          args: [{ name: "player", kind: "text", global: false, action: false, exampleWords: {} }],
        }),
        text({ texts: { en: ["two"] }, key: "b", name: "Second" }),
      ]),
    ),
  )
}

describe("one key open at a time", () => {
  it("draws each key as its name and a rendered line, with no field until it is opened", async () => {
    twoKeys()
    draw(<Page />)

    const row = await screen.findByRole("button", { name: "First" })
    expect(row.textContent).toContain("Hello")
    expect(row.textContent).toContain("player")
    expect(row.textContent).not.toContain("<gray>")
    expect(screen.queryByRole("textbox")).toBeNull()
  })

  it("closes the open key when another is opened, and keeps its draft", async () => {
    twoKeys()
    draw(<Page />)

    await openKey("First")
    const first = await source("First")
    expect(first.value).toBe("<gray>Hello <white>{player}</white></gray>")
    fireEvent.change(first, { target: { value: "Hi {player}" } })
    await openKey("Second")

    expect((await screen.findByRole("textbox", { name: "Second" })).textContent).toBe("two")
    expect(screen.queryByRole("textbox", { name: "First" })).toBeNull()
    screen.getByRole("button", { name: "Save 1" })
  })

  it("drops the drafts of every key with Discard, and the open one shows its stored text again", async () => {
    twoKeys()
    draw(<Page />)

    await openKey("First")
    fireEvent.change(await source("First"), { target: { value: "Hi {player}" } })
    await openKey("Second")
    fireEvent.change(await source("Second"), { target: { value: "zwei" } })
    screen.getByRole("button", { name: "Save 2" })

    fireEvent.click(screen.getByRole("button", { name: "Discard" }))

    expect(asTextArea(screen.getByRole("textbox", { name: "Second" })).value).toBe("two")
    expect(screen.getByRole("button", { name: "First" }).textContent).toContain("Hello")
    expect(screen.queryByRole("button", { name: /Save/ })).toBeNull()
  })
})

describe("placeholders", () => {
  const greeting = listing([
    text({
      texts: { en: ["Hello {player}"] },
      key: "greeting",
      name: "Greeting",
      args: [{ name: "player", kind: "text", global: false, action: false, exampleWords: {} }],
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
    await openKey("Greeting")

    fireEvent.change(await source("Greeting"), { target: { value: "Hello {palyer}" } })

    await screen.findByText("{palyer} is nothing this message offers; it offers player")
    expect(asButton(screen.getByRole("button", { name: "Save 1" })).disabled).toBe(false)
  })

  it("inserts a declared placeholder from the menu where the caret is", async () => {
    vi.stubGlobal("fetch", backend(greeting))
    draw(<Page />)
    await openKey("Greeting")
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
    await openKey("Welcome")

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
    await openKey("Broken")

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
          text({ key: "dm.granted", bundle: "access", name: "Granted", shown: ["DISCORD_MESSAGE"] }),
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
