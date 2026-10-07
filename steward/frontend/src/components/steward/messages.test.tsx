import { useState, type ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { toast } from "sonner"
import { afterEach, describe, expect, it, vi } from "vitest"

import { ServiceSettings } from "@/components/steward/settings"
import { resetDrafts } from "@/lib/drafts"
import { setPendingMessageJump } from "@/lib/settings-search"
import type {
  CommandRun,
  MessageBundle,
  MessageBundleLocation,
  MessageEntry,
  MessageFallback,
  MessageProblem,
  Warning,
} from "@/lib/api"
import { asButton, asTextArea } from "@/lib/test-elements"
import { changesOf, words } from "@/lib/query-fixtures"

vi.mock("sonner", () => ({
  toast: {
    success: vi.fn<(message: string) => void>(),
    warning: vi.fn<(message: string) => void>(),
    info: vi.fn<(message: string) => void>(),
    error: vi.fn<(message: string) => void>(),
  },
}))

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  })
}

/** A bundle's place, and no key a preview reaches unless a test names one. */
function location(
  over: Partial<MessageBundleLocation & Pick<MessageBundle, "languages" | "colours">> & { path: string },
): MessageBundleLocation & Pick<MessageBundle, "previews" | "languages" | "colours"> {
  return {
    service: "smp",
    module: "smp",
    writable: true,
    previews: {},
    languages: [],
    colours: {},
    ...over,
  }
}

function entry(over: Partial<MessageEntry> & { key: string }): MessageEntry {
  return {
    bundle: "smp",
    inBundle: true,
    texts: {},
    overrides: {},
    args: [],
    section: [],
    ...over,
  }
}

/** One `/api/messages/<path>` answer per fixture bundle and one canned PUT answer per path. */
type Bundle = MessageBundle & { warnings?: Warning[] }

/** What the editor asks besides the bundle; a test answers any of them otherwise. */
type Around = {
  fallbacks?: MessageFallback[]
  check?: (text: string) => MessageProblem[]
  /** Answers a preview's body with the request's name. */
  preview?: (body: unknown) => string
  /** What became of each request, by name. */
  commands?: Record<string, CommandRun>
}

function backend(
  bundles: Record<string, Bundle>,
  puts: Record<string, (body: unknown) => unknown> = {},
  around: Around = {},
) {
  const listing = Object.values(bundles).map((bundle) => {
    const { service, module, path, writable } = bundle
    return { service, module, path, writable }
  })
  return vi.fn<(url: string, init?: { method?: string; body?: string }) => Promise<Response>>(async (url, init) => {
    if (url === "/api/messages") return json(listing)
    if (url === "/api/setting-groups") return json([])
    if (url === "/api/message-syntax") return json({ tones: { good: "#8ba888" }, kinds: { duration: ["short"] } })
    if (url === "/api/message-examples") return json({})
    if (url === "/api/message-fallbacks") return json(around.fallbacks ?? [])
    if (url === "/glyphs/manifest.json") return json([])
    if (url.startsWith("/api/message-check?")) {
      return json(around.check?.(new URL(url, "http://steward").searchParams.get("text") ?? "") ?? [])
    }
    if (url === "/api/message-preview" && around.preview) {
      return json({ id: around.preview(JSON.parse(init?.body ?? "")), status: "PENDING" })
    }
    const command = url.startsWith("/api/commands/") ? around.commands?.[url.slice("/api/commands/".length)] : null
    if (command) return json(command)
    if (init?.method === "PUT") {
      const found = Object.entries(puts).find(([path]) => url === `/api/messages/${path}`)
      if (found) return json(found[1](JSON.parse(init.body ?? "")))
      throw new Error(`the form PUT ${url}, which this test did not expect`)
    }
    const found = Object.entries(bundles).find(([path]) => url === `/api/messages/${path}`)
    if (found) return json(found[1])
    throw new Error(`the form asked for ${url}, which this test did not expect`)
  })
}

function draw(node: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(<QueryClientProvider client={queryClient}>{node}</QueryClientProvider>)
}

async function open(label: string) {
  fireEvent.click((await screen.findAllByText(label))[0])
}

/** A key is a row until it is opened; its field only exists once it is. */
async function openKey(name: string) {
  fireEvent.click(await screen.findByRole("button", { name }))
}

/** Switches the open key to its source, B, and hands back the field `label` names. */
async function source(label: string): Promise<HTMLTextAreaElement> {
  fireEvent.click(await screen.findByRole("button", { name: "Source" }))
  return asTextArea(await screen.findByRole("textbox", { name: label }))
}

/** The tab as the service page draws it, with `?file=` kept in state instead of the URL. */
function Settings({ service }: { service: string }) {
  const [file, setFile] = useState<string | undefined>()
  return <ServiceSettings service={service} file={file} onFile={setFile} />
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
  resetDrafts()
})

describe("the bundle row", () => {
  it("lists a bundle by its module name, made readable", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        "smp/smp": {
          ...location({ path: "smp/smp" }),
          entries: [entry({ texts: { en: ["Welcome"], de: ["packaged-de-text"] }, key: "welcome" })],
        },
      }),
    )

    draw(<Settings service="smp" />)

    expect(await screen.findByRole("button", { name: "SMP Translations" })).toBeTruthy()
  })

  it("shows nothing for a service with no bundle here", async () => {
    vi.stubGlobal("fetch", backend({}))

    draw(<Settings service="smp" />)

    expect(await screen.findByText("No files.")).toBeTruthy()
  })
})

describe("the en/de toggle", () => {
  const fixture = {
    "smp/smp": {
      ...location({ path: "smp/smp" }),
      entries: [
        entry({
          texts: { en: ["Welcome"], de: ["packaged-de-text"] },
          overrides: { de: ["override-de-text"] },
          key: "welcome",
        }),
      ],
    },
  }

  it("shows English packaged text by default", async () => {
    vi.stubGlobal("fetch", backend(fixture))
    draw(<Settings service="smp" />)
    await open("SMP Translations")
    await openKey("Welcome")

    expect((await screen.findByRole("textbox", { name: "welcome" })).textContent).toBe("Welcome")
  })

  it("switches to the override once German is selected, rather than the packaged text", async () => {
    vi.stubGlobal("fetch", backend(fixture))
    draw(<Settings service="smp" />)
    await open("SMP Translations")
    await openKey("Welcome")
    await source("welcome")

    fireEvent.mouseDown(screen.getByRole("tab", { name: /DE/ }))

    await screen.findByDisplayValue("override-de-text")
    expect(screen.queryByDisplayValue("packaged-de-text")).toBeNull()
  })
})

describe("saving a line", () => {
  it("warns, but still saves, when the edited text drops a placeholder the packaged text had", async () => {
    vi.stubGlobal(
      "fetch",
      backend(
        {
          "smp/smp": {
            ...location({ path: "smp/smp" }),
            entries: [entry({ texts: { en: ["Hello {sender}"] }, key: "greeting" })],
          },
        },
        {
          "smp/smp": () => ({
            ...location({ path: "smp/smp" }),
            entries: [
              entry({ texts: { en: ["Hello {sender}"] }, overrides: { en: ["Hello there"] }, key: "greeting" }),
            ],
            warnings: [
              {
                key: "greeting",
                language: "en",
                text: { key: "check.value.unshown", args: { role: { kind: "text", value: "sender" } } },
              },
            ],
          }),
        },
      ),
    )
    draw(<Settings service="smp" />)
    await open("SMP Translations")
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
  ] as const)("says a saved line is %s", async (_what, status, shown) => {
    const message = `the service said ${status}`
    vi.stubGlobal(
      "fetch",
      backend(
        {
          "smp/smp": {
            ...location({ path: "smp/smp" }),
            entries: [entry({ texts: { en: ["Welcome"] }, key: "welcome" })],
          },
        },
        {
          "smp/smp": () => ({
            ...location({ path: "smp/smp" }),
            entries: [entry({ texts: { en: ["Welcome"] }, overrides: { en: ["Howdy"] }, key: "welcome" })],
            warnings: [],
            reload: { status, message: words(message) },
          }),
        },
      ),
    )
    draw(<Settings service="smp" />)
    await open("SMP Translations")
    await openKey("Welcome")
    fireEvent.change(await source("welcome"), { target: { value: "Howdy" } })
    fireEvent.click(screen.getByRole("button", { name: /^Save/ }))

    await waitFor(() => expect(shown).toHaveBeenCalledWith("One text saved.", { description: message }))
  })

  it("resets a key by removing the override, not by copying English into it", async () => {
    vi.stubGlobal(
      "fetch",
      backend(
        {
          "smp/smp": {
            ...location({ path: "smp/smp" }),
            entries: [entry({ texts: { en: ["Welcome"] }, overrides: { en: ["Howdy"] }, key: "welcome" })],
          },
        },
        {
          "smp/smp": (body) => {
            const changes = changesOf(body)
            expect(changes).toEqual({ welcome: { en: null } })
            return {
              ...location({ path: "smp/smp" }),
              entries: [entry({ texts: { en: ["Welcome"] }, key: "welcome" })],
              warnings: [],
            }
          },
        },
      ),
    )
    draw(<Settings service="smp" />)
    await open("SMP Translations")
    await openKey("Welcome")
    expect((await source("welcome")).value).toBe("Howdy")

    fireEvent.click(screen.getByRole("button", { name: /Reset/ }))
    await screen.findByDisplayValue("Welcome")
    fireEvent.click(screen.getByRole("button", { name: /^Save/ }))

    /** Both states show the packaged "Welcome", so only the Reset button vanishing proves the save round-tripped. */
    await waitFor(() => expect(screen.queryByRole("button", { name: /Reset/ })).toBeNull())
    expect(screen.getByDisplayValue("Welcome")).toBeTruthy()
    expect(screen.queryByText("overridden")).toBeNull()
  })
})

describe("the tree of a bundle", () => {
  it("names its sections after the spec and shows no keys", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        "smp/smp": {
          ...location({ path: "smp/smp" }),
          entries: [
            entry({
              texts: { en: ["Soon"] },
              key: "grave.decay.warning",
              name: "Decay warning",
              section: ["Graves", "Decay"],
            }),
            entry({ texts: { en: ["Welcome"] }, key: "welcome", name: "Welcome" }),
          ],
        },
      }),
    )
    draw(<Settings service="smp" />)
    await open("SMP Translations")

    const branch = await screen.findByRole("button", { name: /Graves.*Decay/ })
    expect(branch.getAttribute("aria-expanded")).toBe("false")
    expect(screen.queryByText("Decay warning")).toBeNull()

    fireEvent.click(branch)
    screen.getByText("Decay warning")
    expect(screen.queryByText("grave.decay.warning")).toBeNull()
  })
})

/** A location carrying two entries, so opening one can be observed closing the other. */
function twoKeys() {
  vi.stubGlobal(
    "fetch",
    backend({
      "smp/smp": {
        ...location({ path: "smp/smp" }),
        entries: [
          entry({
            texts: { en: ["<gray>Hello <white>{player}</white></gray>"] },
            key: "a",
            name: "First",
            args: [{ name: "player", kind: "text", global: false, action: false, exampleWords: {} }],
          }),
          entry({ texts: { en: ["two"] }, key: "b", name: "Second" }),
        ],
      },
    }),
  )
}

describe("the network's languages and the service's colours", () => {
  const fixture = {
    "smp/smp": {
      ...location({ path: "smp/smp", languages: ["de", "nl"], colours: { good: "#123456" } }),
      entries: [entry({ texts: { en: ["<good>Welcome</good>"] }, key: "welcome", format: "MINIMESSAGE" })],
    },
  }

  it("offers a language the network speaks before any jar ships it", async () => {
    vi.stubGlobal("fetch", backend(fixture))
    draw(<Settings service="smp" />)
    await open("SMP Translations")
    await openKey("Welcome")

    expect(await screen.findByRole("tab", { name: /NL/ })).toBeTruthy()
  })

  it("draws a tone in the colour the service's settings give it", async () => {
    vi.stubGlobal("fetch", backend(fixture))
    draw(<Settings service="smp" />)
    await open("SMP Translations")

    const row = await screen.findByRole("button", { name: "Welcome" })
    await waitFor(() =>
      expect(
        within(row)
          .getAllByText("Welcome")
          .map((span) => span.style.color),
      ).toContain("rgb(18, 52, 86)"),
    )
  })
})

describe("one key open at a time", () => {
  it("draws each key as its name and a rendered line, with no field until it is opened", async () => {
    twoKeys()
    draw(<Settings service="smp" />)
    await open("SMP Translations")

    const row = await screen.findByRole("button", { name: "First" })
    expect(row.textContent).toContain("Hello")
    expect(row.textContent).toContain("player")
    expect(row.textContent).not.toContain("<gray>")
    expect(screen.queryByRole("textbox")).toBeNull()
  })

  it("closes the open key when another is opened, and keeps its draft", async () => {
    twoKeys()
    draw(<Settings service="smp" />)
    await open("SMP Translations")

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
    draw(<Settings service="smp" />)
    await open("SMP Translations")

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

describe("both languages in one save", () => {
  it("sends the English and the German change of two texts in one call", async () => {
    const bodies: unknown[] = []
    vi.stubGlobal(
      "fetch",
      backend(
        {
          "smp/smp": {
            ...location({ path: "smp/smp" }),
            entries: [
              entry({ texts: { en: ["one"], de: ["eins"] }, key: "a", name: "First" }),
              entry({ texts: { en: ["two"], de: ["zwei"] }, key: "b", name: "Second" }),
            ],
          },
        },
        {
          "smp/smp": (body) => {
            bodies.push(body)
            return { ...location({ path: "smp/smp" }), entries: [], warnings: [] }
          },
        },
      ),
    )
    draw(<Settings service="smp" />)
    await open("SMP Translations")

    await openKey("First")
    fireEvent.change(await source("First"), { target: { value: "ONE" } })
    await openKey("Second")
    fireEvent.mouseDown(await screen.findByRole("tab", { name: /DE/ }))
    fireEvent.change(await source("Second"), { target: { value: "ZWEI" } })
    fireEvent.click(screen.getByRole("button", { name: "Save 2" }))

    await waitFor(() => expect(bodies).toHaveLength(1))
    expect(bodies[0]).toEqual({ changes: { a: { en: ["ONE"] }, b: { de: ["ZWEI"] } } })
  })
})

/** A location carrying one entry, with a single declared placeholder. */
function withGreeting() {
  vi.stubGlobal(
    "fetch",
    backend({
      "smp/smp": {
        ...location({ path: "smp/smp" }),
        entries: [
          entry({
            texts: { en: ["Hello {player}"] },
            key: "greeting",
            name: "Greeting",
            args: [{ name: "player", kind: "text", global: false, action: false, exampleWords: {} }],
          }),
        ],
      },
    }),
  )
}

describe("placeholders", () => {
  it("shows what the validator says of the typed text, and leaves the refusal to the save", async () => {
    vi.stubGlobal(
      "fetch",
      backend(
        {
          "smp/smp": {
            ...location({ path: "smp/smp" }),
            entries: [
              entry({
                texts: { en: ["Hello {player}"] },
                key: "greeting",
                name: "Greeting",
                args: [{ name: "player", kind: "text", global: false, action: false, exampleWords: {} }],
              }),
            ],
          },
        },
        {},
        {
          check: (text) =>
            text.includes("{palyer}")
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
        },
      ),
    )
    draw(<Settings service="smp" />)
    await open("SMP Translations")
    await openKey("Greeting")

    fireEvent.change(await source("Greeting"), { target: { value: "Hello {palyer}" } })

    await screen.findByText("{palyer} is nothing this message offers; it offers player")
    expect(asButton(screen.getByRole("button", { name: "Save 1" })).disabled).toBe(false)
  })

  it("inserts a declared placeholder from the menu where the caret is", async () => {
    withGreeting()
    draw(<Settings service="smp" />)
    await open("SMP Translations")
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
    path: "smp/smp",
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
        {
          "smp/smp": {
            ...location({ path: "smp/smp" }),
            entries: [entry({ texts: { en: ["Welcome aboard"] }, overrides: { en: ["Howdy"] }, key: "welcome" })],
          },
        },
        {
          "smp/smp": (body) => {
            bodies.push(body)
            return { ...location({ path: "smp/smp" }), entries: [], warnings: [] }
          },
        },
        { fallbacks: [fallback] },
      ),
    )
    draw(<Settings service="smp" />)
    await open("SMP Translations")
    await openKey("Welcome")

    await screen.findByText("Fallen back")
    expect(screen.getAllByRole("definition").map((text) => text.textContent)).toEqual([
      "Welcome",
      "Welcome aboard",
      "Howdy",
    ])
    fireEvent.click(screen.getByRole("button", { name: "Take over" }))

    await waitFor(() => expect(bodies).toHaveLength(1))
    expect(bodies[0]).toEqual({ changes: { welcome: { en: ["Howdy"] } } })
  })
})

describe("a preview", () => {
  it("sends the text being typed with the values it shows to the admin where the key is shown", async () => {
    const bodies: unknown[] = []
    vi.stubGlobal(
      "fetch",
      backend(
        {
          "smp/smp": {
            ...location({ path: "smp/smp" }),
            previews: { welcome: "GAME" },
            entries: [
              entry({
                texts: { en: ["Welcome {player}"] },
                key: "welcome",
                name: "Welcome",
                args: [
                  { name: "player", kind: "name", global: false, example: "Alex", action: false, exampleWords: {} },
                ],
              }),
              entry({ texts: { en: ["Page"] }, key: "page", name: "Page" }),
            ],
          },
        },
        {},
        {
          preview: (body) => {
            bodies.push(body)
            return "smp:7"
          },
          commands: {
            "smp:7": { id: "smp:7", status: "DONE", result: words("Shown to your player in game.") },
          },
        },
      ),
    )
    draw(<Settings service="smp" />)
    await open("SMP Translations")
    await openKey("Page")
    expect(screen.queryByRole("button", { name: /in game|in Discord/ })).toBeNull()
    await openKey("Welcome")
    fireEvent.change(await source("Welcome"), { target: { value: "Moin {player}" } })

    fireEvent.click(screen.getByRole("button", { name: "Show it to my player in game" }))

    await screen.findByText("Shown to your player in game.")
    expect(bodies).toEqual([
      { bundle: "smp/smp", key: "welcome", language: "en", text: "Moin {player}", values: { player: "Alex" } },
    ])
  })

  it("shows a nested message as its words, and sends the server its key", async () => {
    const bodies: unknown[] = []
    vi.stubGlobal(
      "fetch",
      backend(
        {
          "smp/smp": {
            ...location({ path: "smp/smp" }),
            previews: { "restart.notice": "GAME" },
            entries: [
              entry({
                texts: { en: ["{what} restarts"] },
                key: "restart.notice",
                name: "Restart notice",
                args: [
                  {
                    name: "what",
                    kind: "message",
                    global: false,
                    example: "restart.what.network",
                    action: false,
                    exampleWords: { en: "The network" },
                  },
                ],
              }),
            ],
          },
        },
        {},
        {
          preview: (body) => {
            bodies.push(body)
            return "smp:3"
          },
          commands: {
            "smp:3": { id: "smp:3", status: "DONE", result: words("Shown to your player in game.") },
          },
        },
      ),
    )
    draw(<Settings service="smp" />)
    await open("SMP Translations")
    await openKey("Restart notice")

    expect(await screen.findByText("The network")).toBeTruthy()
    expect(screen.queryByText("restart.what.network")).toBeNull()
    fireEvent.click(screen.getByRole("button", { name: "Show it to my player in game" }))

    await screen.findByText("Shown to your player in game.")
    expect(bodies).toEqual([
      {
        bundle: "smp/smp",
        key: "restart.notice",
        language: "en",
        text: "{what} restarts",
        values: { what: "restart.what.network" },
      },
    ])
  })
})

describe("the two views", () => {
  it("opens a text it cannot read as it is written, and offers no view of it as it looks", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        "smp/smp": {
          ...location({ path: "smp/smp" }),
          entries: [entry({ texts: { en: ["Hello {name"] }, key: "broken", name: "Broken" })],
        },
      }),
    )
    draw(<Settings service="smp" />)
    await open("SMP Translations")
    await openKey("Broken")

    expect(asTextArea(await screen.findByRole("textbox", { name: "Broken" })).value).toBe("Hello {name")
    expect(asButton(screen.getByRole("button", { name: "As it looks" })).disabled).toBe(true)
  })
})

describe("a jump from the command palette", () => {
  it("opens the bundle and switches the text to the language of the hit", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        "smp/smp": {
          ...location({ path: "smp/smp" }),
          entries: [
            entry({ texts: { en: ["Welcome"], de: ["packaged-de-welcome"] }, key: "welcome", name: "Welcome" }),
          ],
        },
      }),
    )
    draw(<Settings service="smp" />)
    await screen.findByRole("button", { name: "SMP Translations" })

    setPendingMessageJump("smp", { path: "smp/smp", language: "de", key: "welcome" })

    await waitFor(() =>
      expect(screen.getByRole("textbox", { name: "Welcome" }).textContent).toBe("packaged-de-welcome"),
    )
  })
})
