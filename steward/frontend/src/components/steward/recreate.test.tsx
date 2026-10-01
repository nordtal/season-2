import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { RecreateButton } from "@/components/steward/recreate"
import { asButton, asElement } from "@/lib/test-elements"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * "Recreate", and the rule that a job which cannot be read is never shown as running.
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

/** The deployer's three routes. `job` is a function so a test can change its mind mid-dialog. */
function backend(
  over: {
    available?: boolean
    reachable?: boolean
    reason?: string
    job?: () => Answer
  } = {},
) {
  return vi.fn<(url: string, init?: RequestInit) => Promise<Response>>(async (url: string, init?: RequestInit) => {
    if (url === "/api/deployer") {
      return json({
        body: {
          available: over.available ?? true,
          reachable: over.reachable ?? true,
          reason: over.reason,
        },
      })
    }
    if (url.startsWith("/api/deployer/recreate/") && init?.method === "POST") {
      return json({ body: { id: "j1", kind: "RECREATE", services: ["smp"], state: "RUNNING", started: "now" } })
    }
    if (url.startsWith("/api/deployer/jobs/")) {
      return json(over.job?.() ?? { body: { id: "j1", state: "RUNNING", lines: [] } })
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

  it("is not drawn at all for the deployer itself", async () => {
    /** The deployer refuses to recreate itself, so no button is drawn for it. */
    draw(<RecreateButton service="steward-deployer" />)

    expect(screen.queryByRole("button")).toBeNull()
  })

  it("is disabled with the deployer's own reason on it when there is no shared secret", async () => {
    fetched = backend({ available: false, reason: "No shared secret has been set up." })
    vi.stubGlobal("fetch", fetched)
    draw(<RecreateButton service="smp" />)

    /** `toBeDisabled` needs jest-dom, which this project does not install. */
    const button = asButton(screen.getByRole("button", { name: /Recreate/ }))
    await waitFor(() => expect(button.disabled).toBe(true))
    expect(button.title).toBe("No shared secret has been set up.")
  })

  it("is disabled when the deployer is configured but its container is not answering", async () => {
    /** `reachable: false` is a measured answer from the endpoint, and answers lock the button. */
    fetched = backend({ available: true, reachable: false })
    vi.stubGlobal("fetch", fetched)
    draw(<RecreateButton service="smp" />)

    const button = asButton(screen.getByRole("button", { name: /Recreate/ }))
    await waitFor(() => expect(button.disabled).toBe(true))
    expect(button.title).toBe("steward-deployer is configured but not answering.")
    expect(button.title).not.toContain("already on this host")
  })

  it("says that nobody in the world is warned, before the button is pressed and not after", async () => {
    draw(<RecreateButton service="smp" />)
    fireEvent.click(screen.getByRole("button", { name: /Recreate/ }))

    const dialog = await screen.findByRole("dialog")
    expect(dialog.textContent).toContain("no countdown")
    expect(dialog.textContent).toContain("thrown out")
    // Opening the dialog must not start a compose run.
    expect(fetched.mock.calls.some(([, init]) => init?.method === "POST")).toBe(false)
  })
})

describe("RecreateButton - when /api/deployer itself cannot be asked", () => {
  it("disables the button and stops claiming a state nobody has checked, on a 404", async () => {
    /** No answer at all: `deployer.data` stays undefined, which must not read as available. */
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => {
        if (url === "/api/deployer") return json({ status: 404, body: { error: "not found", where: "steward" } })
        throw new Error(`the test did not expect ${url}`)
      }),
    )
    draw(<RecreateButton service="smp" />)

    const button = asButton(screen.getByRole("button", { name: /Recreate/ }))
    await waitFor(() => expect(button.disabled).toBe(true))
    expect(button.title).not.toContain("from the image already on this host")
  })
})

describe("RecreateButton - before /api/deployer has answered at all", () => {
  it("keeps the button active but does not claim a state nobody has checked yet", async () => {
    /** The first load leaves the button open but must not claim the confident title. */
    let settle!: (response: Response) => void
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string) => {
        if (url === "/api/deployer") return new Promise<Response>((resolve) => (settle = resolve))
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

describe("RecreateButton - while the job is read", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", backend())
  })

  it("shows compose's own output rather than a summary of it", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        job: () => ({
          body: {
            id: "j1",
            state: "DONE",
            exitCode: 0,
            lines: ["Container nordtal-s2-smp-1  Recreated", "Container nordtal-s2-smp-1  Started"],
          },
        }),
      }),
    )
    draw(<RecreateButton service="smp" />)
    const dialog = await start()

    await waitFor(() => expect(dialog.textContent).toContain("Done"))
    expect(dialog.textContent).toContain("Container nordtal-s2-smp-1  Recreated")
    expect(dialog.textContent).toContain("Exit code 0")
  })

  it("says Failed and the code compose came back with", async () => {
    vi.stubGlobal(
      "fetch",
      backend({ job: () => ({ body: { id: "j1", state: "FAILED", exitCode: 1, lines: ["no such service: smp"] } }) }),
    )
    draw(<RecreateButton service="smp" />)
    const dialog = await start()

    await waitFor(() => expect(dialog.textContent).toContain("Failed"))
    expect(dialog.textContent).toContain("Exit code 1")
    expect(within(dialog).queryByText("Running")).toBeNull()
  })

  it("shows the failure, and not Running, when the job cannot be read at all", async () => {
    /** A job nobody has answered about must not draw the working badge. */
    vi.stubGlobal(
      "fetch",
      backend({
        job: () => ({ status: 502, body: { error: "steward-deployer is not answering.", where: "steward-deployer" } }),
      }),
    )
    draw(<RecreateButton service="smp" />)
    const dialog = await start()

    await waitFor(() => expect(within(dialog).getByRole("alert")).toBeTruthy())
    expect(within(dialog).getByRole("alert").textContent).toContain("steward-deployer is not answering.")
    expect(within(dialog).queryByText("Running")).toBeNull()
    // The sentence that tells an operator what a silent deployer costs them.
    expect(within(dialog).getByRole("alert").textContent).toContain("nothing can be")
  })

  it("offers to ask again rather than leaving the failure as the last word", async () => {
    let broken = true
    vi.stubGlobal(
      "fetch",
      backend({
        job: () =>
          broken
            ? { status: 502, body: { error: "steward-deployer is not answering.", where: "steward-deployer" } }
            : { body: { id: "j1", state: "DONE", exitCode: 0, lines: ["Container nordtal-s2-smp-1  Recreated"] } },
      }),
    )
    draw(<RecreateButton service="smp" />)
    const dialog = await start()

    await waitFor(() => expect(within(dialog).getByRole("alert")).toBeTruthy())
    broken = false
    fireEvent.click(within(dialog).getByRole("button", { name: /Try again/ }))

    await waitFor(() => expect(dialog.textContent).toContain("Done"))
    expect(within(dialog).queryByRole("alert")).toBeNull()
  })
})

describe("RecreateButton - the footer while the job is unreadable", () => {
  it("lets the operator out again after a poll that failed", async () => {
    // `running` carries the `!job.error` guard, since `job.data` survives a failed poll.
    let broken = false
    vi.stubGlobal(
      "fetch",
      backend({
        job: () =>
          broken
            ? { status: 502, body: { error: "steward-deployer is not answering.", where: "steward-deployer" } }
            : { body: { id: "j1", state: "RUNNING", lines: ["Container nordtal-s2-smp-1  Recreating"] } },
      }),
    )
    draw(<RecreateButton service="smp" />)
    const dialog = await start()

    await waitFor(() => expect(dialog.textContent).toContain("Recreating"))
    broken = true
    await waitFor(() => expect(within(dialog).queryByRole("alert")).not.toBeNull(), { timeout: 3000 })

    /** Scoped to the footer, since Radix's dismiss icon is also named "Close". */
    const footer = asElement(dialog.querySelector('[data-slot="dialog-footer"]'))
    const close = asButton(within(footer).getByRole("button", { name: /Close|Running/ }))
    expect([close.textContent, close.disabled]).toEqual(["Close", false])
  })
})
