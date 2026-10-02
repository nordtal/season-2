import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { RecreateButton } from "@/components/steward/recreate"
import { asButton } from "@/lib/test-elements"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * "Recreate", which asks for a run and never touches a container itself.
 *
 * The fake backend answers by URL, since the dialog asks three questions in an order that may change.
 */

type Answer = { status?: number; body: unknown }

function json({ status = 200, body }: Answer): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

/** The agent's state and the run inbox. */
function backend(
  over: {
    available?: boolean
    reachable?: boolean
    reason?: string
    ask?: () => Answer
  } = {},
) {
  return vi.fn<(url: string, init?: RequestInit) => Promise<Response>>(async (url: string, init?: RequestInit) => {
    if (url === "/api/agent") {
      return json({
        body: {
          available: over.available ?? true,
          reachable: over.reachable ?? true,
          reason: over.reason,
        },
      })
    }
    if (url === "/api/updates" && init?.method === "POST") {
      return json(over.ask?.() ?? { status: 202, body: { id: 77, kind: "RECREATE", status: "PENDING" } })
    }
    throw new Error(`the dialog asked for ${url}, which this test did not expect`)
  })
}

function draw(node: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  /** The Shell's tooltip provider, without which `StatusBadge` throws. */
  return render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>{node}</TooltipProvider>
    </QueryClientProvider>,
  )
}

/** Open the dialog and press the confirming "Recreate" inside it, not the trigger. */
async function start() {
  fireEvent.click(screen.getByRole("button", { name: /Recreate/ }))
  const dialog = await screen.findByRole("dialog")
  fireEvent.click(within(dialog).getByRole("button", { name: "Recreate" }))
  return dialog
}

let fetched: ReturnType<typeof backend>

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("RecreateButton - before anything is pressed", () => {
  beforeEach(() => {
    fetched = backend()
    vi.stubGlobal("fetch", fetched)
  })

  it("is not drawn at all for the agent itself", async () => {
    /** The agent refuses to recreate itself, so no button is drawn for it. */
    draw(<RecreateButton service="steward-agent" />)

    expect(screen.queryByRole("button")).toBeNull()
  })

  it("is disabled with the agent's own reason on it when there is no shared secret", async () => {
    fetched = backend({ available: false, reason: "No shared secret has been set up." })
    vi.stubGlobal("fetch", fetched)
    draw(<RecreateButton service="smp" />)

    /** `toBeDisabled` needs jest-dom, which this project does not install. */
    const button = asButton(screen.getByRole("button", { name: /Recreate/ }))
    await waitFor(() => expect(button.disabled).toBe(true))
    expect(button.title).toBe("No shared secret has been set up.")
  })

  it("is disabled when the agent is configured but its container is not answering", async () => {
    /** `reachable: false` is a measured answer from the endpoint, and answers lock the button. */
    fetched = backend({ available: true, reachable: false })
    vi.stubGlobal("fetch", fetched)
    draw(<RecreateButton service="smp" />)

    const button = asButton(screen.getByRole("button", { name: /Recreate/ }))
    await waitFor(() => expect(button.disabled).toBe(true))
    expect(button.title).toBe("steward-agent is configured but not answering.")
    expect(button.title).not.toContain("already on this host")
  })

  it("says that it is a run with a warning, and opening it asks for nothing", async () => {
    draw(<RecreateButton service="smp" />)
    fireEvent.click(screen.getByRole("button", { name: /Recreate/ }))

    const dialog = await screen.findByRole("dialog")
    expect(dialog.textContent).toContain("warned")
    expect(fetched.mock.calls.some(([, init]) => init?.method === "POST")).toBe(false)
  })
})

describe("RecreateButton - when /api/agent itself cannot be asked", () => {
  it("disables the button and stops claiming a state nobody has checked, on a 404", async () => {
    /** No answer at all: `agent.data` stays undefined, which must not read as available. */
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => {
        if (url === "/api/agent") return json({ status: 404, body: { error: "not found", where: "steward" } })
        throw new Error(`the test did not expect ${url}`)
      }),
    )
    draw(<RecreateButton service="smp" />)

    const button = asButton(screen.getByRole("button", { name: /Recreate/ }))
    await waitFor(() => expect(button.disabled).toBe(true))
    expect(button.title).not.toContain("from the image already on this host")
  })
})

describe("RecreateButton - before /api/agent has answered at all", () => {
  it("keeps the button active but does not claim a state nobody has checked yet", async () => {
    /** The first load leaves the button open but must not claim the confident title. */
    let settle!: (response: Response) => void
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string) => {
        if (url === "/api/agent") return new Promise<Response>((resolve) => (settle = resolve))
        throw new Error(`the test did not expect ${url}`)
      }),
    )
    draw(<RecreateButton service="smp" />)

    const button = asButton(screen.getByRole("button", { name: /Recreate/ }))
    expect(button.disabled).toBe(false)
    expect(button.title).not.toContain("from the image already on this host")

    // Resolves the pending fetch so no timer leaks into the next test.
    settle(json({ body: { available: true, reachable: true } }))
    await waitFor(() => expect(button.title).toContain("from the image already on this host"))
  })
})

describe("RecreateButton - pressed", () => {
  beforeEach(() => {
    fetched = backend()
    vi.stubGlobal("fetch", fetched)
  })

  it("asks for a RECREATE run naming this one service", async () => {
    draw(<RecreateButton service="smp" />)
    await start()

    await waitFor(() => expect(fetched.mock.calls.some(([url]) => url === "/api/updates")).toBe(true))
    const [, init] = fetched.mock.calls.find(([url]) => url === "/api/updates") ?? []
    expect(JSON.parse(typeof init?.body === "string" ? init.body : "")).toEqual({ kind: "RECREATE", services: ["smp"] })
    expect(fetched.mock.calls.some(([url]) => url.startsWith("/api/agent/"))).toBe(false)
  })

  it("keeps the dialog open when the inbox refuses, since another run is open", async () => {
    fetched = backend({ ask: () => ({ status: 409, body: { error: "a run is already open", where: "steward" } }) })
    vi.stubGlobal("fetch", fetched)
    draw(<RecreateButton service="smp" />)
    const dialog = await start()

    await waitFor(() => expect(fetched.mock.calls.some(([url]) => url === "/api/updates")).toBe(true))
    expect(within(dialog).getByRole("button", { name: "Recreate" })).toBeTruthy()
  })
})
