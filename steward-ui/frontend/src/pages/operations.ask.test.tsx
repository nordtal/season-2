import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { AskButton } from "@/pages/operations"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * The confirmation before a run offers Now or Cancel, and says what happens in at most two short
 * lines. A run for later went, in the whole interface: nothing but this dialog ever used it.
 */

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function open(kind: "UPDATE" | "BACKUP" | "RESTART" | "DOWN" | "START") {
  vi.stubGlobal("fetch", vi.fn(async () => new Response("{}", { status: 200 })))
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
