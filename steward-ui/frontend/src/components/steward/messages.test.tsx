import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { toast } from "sonner"
import { afterEach, describe, expect, it, vi } from "vitest"

import { ServiceMessages } from "@/components/steward/messages"
import type { MessageBundleLocation, MessageEntry } from "@/lib/api"

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), warning: vi.fn(), info: vi.fn(), error: vi.fn() },
}))

/**
 * `ServiceMessages` is the card steward/48 asks for - a service's message bundles, drawn beside
 * its configuration cards rather than inside them, with the packaged jar text and the operator's
 * override for one line sitting next to each other under an en/de toggle.
 *
 * Every scenario here was RED against the state before this file and `messages.tsx` existed at
 * all: there was no `ServiceMessages` export, so every test failed on the import itself. That
 * failure, verbatim, belongs in the ticket's report rather than in this comment.
 */

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
    inBundle: true,
    args: [],
    section: [],
    ...over,
  }
}

/** One `/api/messages/<path>` answer per fixture bundle and one canned PUT answer per path. */
function backend(
  bundles: Record<string, unknown>,
  puts: Record<string, (body: unknown) => unknown> = {},
) {
  const listing = Object.values(bundles).map((bundle) => {
    const { service, module, path, writable } = bundle as MessageBundleLocation
    return { service, module, path, writable }
  })
  return vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/messages") return json(listing)
    if (init?.method === "PUT") {
      const found = Object.entries(puts).find(([path]) => url === `/api/messages/${path}`)
      if (found) return json(found[1](JSON.parse(String(init.body))))
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
  fireEvent.click(await screen.findByText(label))
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe("the bundle row", () => {
  it("lists a bundle by its module name, in its own Messages card", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        "smp/smp": {
          ...location({ path: "smp/smp" }),
          entries: [entry({ key: "welcome", english: "Welcome", german: "packaged-de-text" })],
        },
      }),
    )

    draw(<ServiceMessages service="smp" />)

    await screen.findByText("Messages")
    await screen.findByText("smp")
  })

  it("shows nothing for a service with no bundle here", async () => {
    vi.stubGlobal("fetch", backend({}))

    draw(<ServiceMessages service="smp" />)

    await screen.findByText("Messages")
    await screen.findByText(/has no message bundle/)
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
    draw(<ServiceMessages service="smp" />)
    await open("smp")

    await screen.findByDisplayValue("Welcome")
  })

  it("switches to the override once German is selected, rather than the packaged text", async () => {
    vi.stubGlobal("fetch", backend(fixture))
    draw(<ServiceMessages service="smp" />)
    await open("smp")
    await screen.findByDisplayValue("Welcome")

    fireEvent.click(screen.getByRole("button", { name: "DE" }))

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
            entries: [entry({ key: "greeting", english: "Hello <_sender>" })],
          },
        },
        {
          "smp/smp": () => ({
            ...location({ path: "smp/smp" }),
            entries: [
              entry({ key: "greeting", english: "Hello <_sender>", overrideEnglish: "Hello there" }),
            ],
            warnings: ["greeting no longer contains <_sender>"],
          }),
        },
      ),
    )
    draw(<ServiceMessages service="smp" />)
    await open("smp")
    const field = await screen.findByDisplayValue("Hello <_sender>")
    fireEvent.change(field, { target: { value: "Hello there" } })
    fireEvent.click(screen.getByRole("button", { name: /Save/ }))

    await screen.findByText(/no longer contains <_sender>/)
    await screen.findByDisplayValue("Hello there")
  })

  /** The save's own answer says whether the text is in force; the toast says which of three. */
  it.each([
    ["in force", "APPLIED", toast.success],
    ["not answered", "NO_ANSWER", toast.warning],
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
            reload: { status, message, unknown: [] },
          }),
        },
      ),
    )
    draw(<ServiceMessages service="smp" />)
    await open("smp")
    const field = await screen.findByDisplayValue("Welcome")
    fireEvent.change(field, { target: { value: "Howdy" } })
    fireEvent.click(screen.getByRole("button", { name: /Save/ }))

    await waitFor(() =>
      expect(shown).toHaveBeenCalledWith("One text saved.", { description: message }),
    )
  })

  it("names a key the override file has and the bundle does not", async () => {
    vi.stubGlobal(
      "fetch",
      backend(
        {
          "discord-bot": {
            ...location({ path: "discord-bot", service: "discord-bot", module: "" }),
            entries: [entry({ key: "dm.granted", english: "You are in" })],
          },
        },
        {
          "discord-bot": () => ({
            ...location({ path: "discord-bot", service: "discord-bot", module: "" }),
            entries: [
              entry({ key: "dm.granted", english: "You are in", overrideEnglish: "Welcome in" }),
            ],
            warnings: [],
            reload: {
              status: "APPLIED",
              message: "The bot re-read its messages. It has no key called dm.grantd.",
              unknown: ["dm.grantd"],
            },
          }),
        },
      ),
    )
    draw(<ServiceMessages service="discord-bot" />)
    await open("discord-bot")
    const field = await screen.findByDisplayValue("You are in")
    fireEvent.change(field, { target: { value: "Welcome in" } })
    fireEvent.click(screen.getByRole("button", { name: /Save/ }))

    await screen.findByText(/dm.grantd is in the override file and in no bundle/)
  })

  it("resets a key by removing the override, not by copying English into it", async () => {
    vi.stubGlobal(
      "fetch",
      backend(
        {
          "smp/smp": {
            ...location({ path: "smp/smp" }),
            entries: [
              entry({ key: "welcome", english: "Welcome", overrideEnglish: "Howdy" }),
            ],
          },
        },
        {
          "smp/smp": (body) => {
            const changes = (body as { changes: Record<string, unknown> }).changes
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
    draw(<ServiceMessages service="smp" />)
    await open("smp")
    await screen.findByDisplayValue("Howdy")

    fireEvent.click(screen.getByRole("button", { name: /Reset/ }))
    fireEvent.click(screen.getByRole("button", { name: /Save/ }))

    // Both the reset-pending preview and the saved result show "Welcome" - packaged text, since
    // the override is gone either way - so a display-value match alone cannot tell the two apart.
    // The Reset button itself can: it only draws while an override exists, so its disappearance is
    // the one signal that is true only once the save has actually round-tripped.
    await waitFor(() => expect(screen.queryByRole("button", { name: /Reset/ })).toBeNull())
    expect(screen.getByDisplayValue("Welcome")).toBeTruthy()
    expect(screen.queryByText("overridden")).toBeNull()
  })
})
