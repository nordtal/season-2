import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { RecreateButton } from "@/components/steward/recreate"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * "Recreate", and the one sentence it must never say by accident.
 *
 * A job that cannot be read is **not** a job that is running. Defaulting to "Running" and three dots
 * said exactly the same thing as a compose run in progress, in the one situation where the
 * difference matters most: the container may already be down and the only process allowed to bring
 * it back is the one that has stopped answering.
 *
 * The fake backend below answers by URL rather than by call count, because the dialog asks three
 * different questions - is the deployer there, please recreate, what became of it - and a queue of
 * responses would silently re-order itself the moment the component changed when it asks what.
 */

type Answer = { status?: number; body: unknown }

function json({ status = 200, body }: Answer): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

/** The deployer's three routes. `job` is a function so a test can change its mind mid-dialog. */
function backend(over: { available?: boolean; reason?: string; job?: () => Answer } = {}) {
  return vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/deployer") {
      return json({ body: { available: over.available ?? true, reason: over.reason } })
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
  // The same provider the Shell puts around everything: `StatusBadge` is a Radix tooltip and
  // throws without one, which would be a test failing for a reason the component does not have.
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
    // It is the container the request travels through, so it refuses to recreate itself. A button
    // that is drawn and then refused is a button that teaches an operator to distrust the page.
    draw(<RecreateButton service="steward-deployer" />)

    expect(screen.queryByRole("button")).toBeNull()
  })

  it("is disabled with the deployer's own reason on it when there is no shared secret", async () => {
    // "not set up" is a different sentence from "broken", and only the deployer knows which it
    // is.
    fetched = backend({ available: false, reason: "No shared secret has been set up." })
    vi.stubGlobal("fetch", fetched)
    draw(<RecreateButton service="smp" />)

    // `toBeDisabled` would need jest-dom, which this project does not install - the property is
    // the same assertion and one dependency fewer.
    const button = screen.getByRole("button", { name: /Recreate/ }) as HTMLButtonElement
    await waitFor(() => expect(button.disabled).toBe(true))
    expect(button.title).toBe("No shared secret has been set up.")
  })

  it("says that nobody in the world is warned, before the button is pressed and not after", async () => {
    // The whole argument for the dialog: an update counts down in front of every player, this
    // does not. Putting that in front of the button is cheaper than explaining it afterwards.
    draw(<RecreateButton service="smp" />)
    fireEvent.click(screen.getByRole("button", { name: /Recreate/ }))

    const dialog = await screen.findByRole("dialog")
    expect(dialog.textContent).toContain("no countdown")
    expect(dialog.textContent).toContain("thrown out")
    // Nothing has been asked for yet - opening the dialog must not start a compose run.
    expect(fetched.mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === "POST")).toBe(false)
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
          body: { id: "j1", state: "DONE", exitCode: 0, lines: ["Container nordtal-s2-smp-1  Recreated", "Container nordtal-s2-smp-1  Started"] },
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
    // The defect this replaced: `job.data?.state ?? "RUNNING"` drew the working badge for a job
    // nothing had ever answered about.
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
  it(
    "lets the operator out again after a poll that failed",
    async () => {
      /*
       * This was a defect when it was written. `running` was
       * `recreate.isPending || job.data?.state === "RUNNING"`, and `job.data` survives a failed
       * poll - which is exactly what the body of the dialog had just been corrected for. So once
       * one poll had answered RUNNING and the next one failed, the body correctly said the deployer
       * is not answering while the footer said "Running…", the close button was disabled, and
       * `onOpenChange` refused Escape and the overlay too: shut in a dialog that had just announced
       * nothing more is coming. `running` now carries the same `!job.error` guard that
       * `refetchInterval` uses in queries.ts.
       */
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

      // The body is right and the footer is not. Scoped to the footer on purpose: Radix's own
      // dismiss icon carries the sr-only name "Close" too, so an unscoped query by that name finds
      // two buttons and the one this assertion is about is the second.
      const footer = dialog.querySelector('[data-slot="dialog-footer"]') as HTMLElement
      const close = within(footer).getByRole("button", { name: /Close|Running/ }) as HTMLButtonElement
      expect([close.textContent, close.disabled]).toEqual(["Close", false])
    },
  )
})
