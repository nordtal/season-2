import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { AskButton } from "@/pages/operations"
import { TooltipProvider } from "@/components/ui/tooltip"

/** The confirmation before a run offers Now or Cancel and says what happens in at most two short lines. */

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function open(kind: "UPDATE" | "BACKUP" | "RESTART" | "DOWN" | "START") {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response("{}", { status: 200 })),
  )
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <TooltipProvider>
        <AskButton kind={kind} services={["smp"]} label="Go" />
      </TooltipProvider>
    </QueryClientProvider>,
  )
  fireEvent.click(screen.getByRole("button", { name: "Go" }))
  return screen.getByRole("alertdialog")
}

describe("the run dialog", () => {
  for (const kind of ["UPDATE", "BACKUP", "RESTART", "DOWN", "START"] as const) {
    it(`asks ${kind} as Now or Cancel, in two lines at most`, () => {
      const dialog = open(kind)
      const buttons = [...dialog.querySelectorAll("button")].map((button) => button.textContent?.trim())
      expect(buttons).toEqual(["Cancel", "Now"])
      expect(dialog.textContent).not.toMatch(/tonight/i)
      expect(dialog.querySelectorAll("p").length).toBeLessThanOrEqual(2)
    })
  }
})

function askUpdate(services: string[]) {
  const posted: unknown[] = []
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith("/api/services")) {
        return Response.json({
          services: [
            { service: "smp", drift: "UP_TO_DATE", localBuild: { jars: ["smp-0.17.0.jar"] } },
            { service: "limbo", drift: "UP_TO_DATE" },
          ],
          moving: [],
        })
      }
      if (typeof init?.body === "string") posted.push(JSON.parse(init.body))
      return Response.json({ id: 7 })
    }),
  )
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <TooltipProvider>
        <AskButton kind="UPDATE" services={services} label="Go" />
      </TooltipProvider>
    </QueryClientProvider>,
  )
  fireEvent.click(screen.getByRole("button", { name: "Go" }))
  return posted
}

describe("an update over a build made on the host", () => {
  it("names the jar in the confirmation and says the build may go", async () => {
    const posted = askUpdate(["smp"])
    expect(await screen.findByText(/smp \(smp-0\.17\.0\.jar\)/)).toBeTruthy()
    fireEvent.click(screen.getByRole("button", { name: "Now" }))
    await vi.waitFor(() => expect(posted).toEqual([{ kind: "UPDATE", services: ["smp"], replaceLocal: true }]))
  })

  it("says nothing of a build outside the run's scope, and asks the agent to keep any it would replace", async () => {
    const posted = askUpdate(["limbo"])
    fireEvent.click(screen.getByRole("button", { name: "Now" }))
    await vi.waitFor(() => expect(posted).toEqual([{ kind: "UPDATE", services: ["limbo"] }]))
    expect(screen.queryByText(/smp-0\.17\.0\.jar/)).toBeNull()
  })
})
