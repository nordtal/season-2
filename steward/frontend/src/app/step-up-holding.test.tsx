import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { StepUpHolding } from "@/app/user-menu"

/** The account popover's step-up row: shown while a step-up holds, and ending it at once. */
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function show(until: string | undefined) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <StepUpHolding until={until} />
    </QueryClientProvider>,
  )
}

const inAnHour = () => new Date(Date.now() + 60 * 60 * 1000).toISOString()

describe("StepUpHolding", () => {
  it("is drawn only while a step-up holds", () => {
    show(undefined)
    expect(screen.queryByRole("button", { name: "End now" })).toBeNull()
    cleanup()

    show(new Date(Date.now() - 1000).toISOString())
    expect(screen.queryByRole("button", { name: "End now" })).toBeNull()
    cleanup()

    show(inAnHour())
    expect(screen.getByText(/^Every change allowed until /)).toBeTruthy()
  })

  it("ends the step-up with one DELETE", async () => {
    const calls: { url: string; method: string }[] = []
    vi.stubGlobal("fetch", async (url: string, init?: RequestInit) => {
      calls.push({ url, method: init?.method ?? "GET" })
      return new Response(null, { status: 204 })
    })
    show(inAnHour())

    fireEvent.click(screen.getByRole("button", { name: "End now" }))

    await waitFor(() => expect(calls).toContainEqual({ url: "/api/step-up", method: "DELETE" }))
  })
})
