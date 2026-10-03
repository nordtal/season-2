import { useState, type ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { toast } from "sonner"
import { afterEach, describe, expect, it, vi } from "vitest"

import { ServiceSettings } from "@/components/steward/settings"
import { resetDrafts } from "@/lib/drafts"
import { setPendingMessageJump } from "@/lib/settings-search"
import type { MessageBundle, MessageBundleLocation, MessageEntry } from "@/lib/api"
import { asButton, asTextArea } from "@/lib/test-elements"
import { changesOf } from "@/lib/query-fixtures"

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

function location(over: Partial<MessageBundleLocation> & { path: string }): MessageBundleLocation {
  return {
    service: "smp",
    module: "smp",
    writable: true,
    ...over,
  }
}

function entry(over: Partial<MessageEntry> & { key: string }): MessageEntry {
  return {
    bundle: "smp",
    inBundle: true,
    englishTexts: [],
    germanTexts: [],
    args: [],
    section: [],
    ...over,
  }
}

/** One `/api/messages/<path>` answer per fixture bundle and one canned PUT answer per path. */
type Bundle = MessageBundle & { warnings?: string[] }

function backend(bundles: Record<string, Bundle>, puts: Record<string, (body: unknown) => unknown> = {}) {
  const listing = Object.values(bundles).map((bundle) => {
    const { service, module, path, writable } = bundle
    return { service, module, path, writable }
  })
  return vi.fn<(url: string, init?: { method?: string; body?: string }) => Promise<Response>>(async (url, init) => {
    if (url === "/api/messages") return json(listing)
    if (url === "/api/setting-groups") return json([])
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

/** A screen as wide as the two-column layout, where a key opens inline instead of in a sheet. */
function wide() {
  vi.stubGlobal("matchMedia", (query: string) => ({
    matches: query.includes("64rem"),
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }))
}

/** A key is a row until it is opened; its field only exists once it is. */
async function openKey(name: string) {
  fireEvent.click(await screen.findByRole("button", { name }))
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
          entries: [entry({ key: "welcome", english: "Welcome", german: "packaged-de-text" })],
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
          key: "welcome",
          english: "Welcome",
          german: "packaged-de-text",
          overrideGerman: "override-de-text",
        }),
      ],
    },
  }

  it("shows English packaged text by default", async () => {
    vi.stubGlobal("fetch", backend(fixture))
    draw(<Settings service="smp" />)
    await open("SMP Translations")
    await openKey("Welcome")

    expect(await screen.findByDisplayValue("Welcome")).toBeTruthy()
  })

  it("switches to the override once German is selected, rather than the packaged text", async () => {
    vi.stubGlobal("fetch", backend(fixture))
    draw(<Settings service="smp" />)
    await open("SMP Translations")
    await openKey("Welcome")
    await screen.findByDisplayValue("Welcome")

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
            entries: [entry({ key: "greeting", english: "Hello {sender}" })],
          },
        },
        {
          "smp/smp": () => ({
            ...location({ path: "smp/smp" }),
            entries: [entry({ key: "greeting", english: "Hello {sender}", overrideEnglish: "Hello there" })],
            warnings: ["greeting no longer contains {sender}"],
          }),
        },
      ),
    )
    draw(<Settings service="smp" />)
    await open("SMP Translations")
    await openKey("Greeting")
    const field = await screen.findByDisplayValue("Hello {sender}")
    fireEvent.change(field, { target: { value: "Hello there" } })
    fireEvent.click(screen.getByRole("button", { name: /^Save/ }))

    expect(await screen.findByText(/no longer contains {sender}/)).toBeTruthy()
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
            entries: [entry({ key: "welcome", english: "Welcome" })],
          },
        },
        {
          "smp/smp": () => ({
            ...location({ path: "smp/smp" }),
            entries: [entry({ key: "welcome", english: "Welcome", overrideEnglish: "Howdy" })],
            warnings: [],
            reload: { status, message },
          }),
        },
      ),
    )
    draw(<Settings service="smp" />)
    await open("SMP Translations")
    await openKey("Welcome")
    const field = await screen.findByDisplayValue("Welcome")
    fireEvent.change(field, { target: { value: "Howdy" } })
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
            entries: [entry({ key: "welcome", english: "Welcome", overrideEnglish: "Howdy" })],
          },
        },
        {
          "smp/smp": (body) => {
            const changes = changesOf(body)
            expect(changes).toEqual({ welcome: { en: null } })
            return {
              ...location({ path: "smp/smp" }),
              entries: [entry({ key: "welcome", english: "Welcome" })],
              warnings: [],
            }
          },
        },
      ),
    )
    draw(<Settings service="smp" />)
    await open("SMP Translations")
    await openKey("Welcome")
    await screen.findByDisplayValue("Howdy")

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
            entry({ key: "grave.decay.warning", name: "Decay warning", section: ["Graves", "Decay"], english: "Soon" }),
            entry({ key: "welcome", name: "Welcome", english: "Welcome" }),
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
            key: "a",
            name: "First",
            english: "<gray>Hello <white>{player}</white></gray>",
            args: [{ name: "player", kind: "text", global: false, action: false }],
          }),
          entry({ key: "b", name: "Second", english: "two" }),
        ],
      },
    }),
  )
}

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
    wide()
    twoKeys()
    draw(<Settings service="smp" />)
    await open("SMP Translations")

    await openKey("First")
    fireEvent.change(await screen.findByDisplayValue("<gray>Hello <white>{player}</white></gray>"), {
      target: { value: "Hi {player}" },
    })
    await openKey("Second")

    await screen.findByDisplayValue("two")
    expect(screen.queryByDisplayValue("Hi {player}")).toBeNull()
    expect(screen.getAllByRole("textbox")).toHaveLength(1)
    screen.getByRole("button", { name: "Save 1" })
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
              entry({ key: "a", name: "First", english: "one", german: "eins" }),
              entry({ key: "b", name: "Second", english: "two", german: "zwei" }),
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
    fireEvent.change(await screen.findByDisplayValue("one"), { target: { value: "ONE" } })
    fireEvent.click(screen.getByRole("button", { name: "Done" }))
    await openKey("Second")
    fireEvent.mouseDown(screen.getByRole("tab", { name: /DE/ }))
    fireEvent.change(await screen.findByDisplayValue("zwei"), { target: { value: "ZWEI" } })
    fireEvent.click(screen.getByRole("button", { name: "Save 2" }))

    await waitFor(() => expect(bodies).toHaveLength(1))
    expect(bodies[0]).toEqual({ changes: { a: { en: "ONE" }, b: { de: "ZWEI" } } })
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
            key: "greeting",
            name: "Greeting",
            english: "Hello {player}",
            args: [{ name: "player", kind: "text", global: false, action: false }],
          }),
        ],
      },
    }),
  )
}

describe("placeholders", () => {
  it("refuses to save a placeholder the text does not declare, and says which", async () => {
    withGreeting()
    draw(<Settings service="smp" />)
    await open("SMP Translations")
    await openKey("Greeting")

    fireEvent.change(await screen.findByDisplayValue("Hello {player}"), { target: { value: "Hello {palyer}" } })

    await screen.findByText("Unknown placeholder {palyer}")
    expect(asButton(screen.getByRole("button", { name: "Save 1" })).disabled).toBe(true)
  })

  it("inserts a declared placeholder from its badge", async () => {
    withGreeting()
    draw(<Settings service="smp" />)
    await open("SMP Translations")
    await openKey("Greeting")
    const field = asTextArea(await screen.findByDisplayValue("Hello {player}"))
    field.setSelectionRange(0, 0)

    fireEvent.click(screen.getByRole("button", { name: "{player}" }))

    expect(await screen.findByDisplayValue("{player}Hello {player}")).toBeTruthy()
  })
})

describe("a jump from the command palette", () => {
  it("opens the bundle and switches the text to the language of the hit", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        "smp/smp": {
          ...location({ path: "smp/smp" }),
          entries: [entry({ key: "welcome", name: "Welcome", english: "Welcome", german: "packaged-de-welcome" })],
        },
      }),
    )
    draw(<Settings service="smp" />)
    await screen.findByRole("button", { name: "SMP Translations" })

    setPendingMessageJump("smp", { path: "smp/smp", language: "de", key: "welcome" })

    expect(await screen.findByDisplayValue("packaged-de-welcome")).toBeTruthy()
  })
})
