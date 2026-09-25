import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { HungerGamesActions, SmpActions, keyName } from "@/components/steward/game-actions"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * Decided 2026-09-20: these actions must never look like commands in any way. These hold that the smp and hunger-games actions are picked, not typed; that they are
 * asked for by what they act on; and that the late answer - EXPIRED above all - is said in words.
 */

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

const TRACK = {
  active: [
    {
      key: "frontier",
      objectives: [
        { key: "netherite-scrap", type: "HAND_IN", amount: 64, target: 128, completed: false },
        { key: "shulker-shells", type: "HAND_IN", amount: 16, target: 16, completed: true },
      ],
    },
  ],
}

type Row = { status: string; result?: string }

function backend({
  row = { status: "DONE", result: "Objective closed." },
  round = { state: "REGISTRATION", registered: 4 } as Record<string, unknown>,
  roundAfter,
}: { row?: Row; round?: Record<string, unknown>; roundAfter?: Record<string, unknown> } = {}) {
  const sent: { url: string; body: unknown }[] = []
  let answered = false
  const fetched = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") {
      sent.push({ url, body: JSON.parse(String(init.body)) })
      return json(202, { id: String(sent.length), status: "PENDING" })
    }
    if (url === "/api/smp/track") return json(200, TRACK)
    if (url === "/api/hunger-games/round") return json(200, answered && roundAfter ? roundAfter : round)
    if (url.startsWith("/api/commands/")) {
      answered = true
      return json(200, { id: url.split("/").pop(), ...row })
    }
    throw new Error(`the card asked for ${url}, which this test did not expect`)
  })
  vi.stubGlobal("fetch", fetched)
  return sent
}

function draw(node: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>{node}</TooltipProvider>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("keyName", () => {
  it("reads a track key as a name", () => {
    expect(keyName("ancient-debris")).toBe("Ancient debris")
    expect(keyName("frontier")).toBe("Frontier")
  })
})

describe("SmpActions", () => {
  it("lists the active milestone's objectives by name, and names no command", async () => {
    backend()
    draw(<SmpActions />)

    expect(await screen.findByText("Netherite scrap")).toBeTruthy()
    expect(screen.getByText("64 / 128")).toBeTruthy()
    expect(screen.queryByRole("button", { name: "Complete Shulker shells" })).toBeNull()
    expect(screen.queryByRole("textbox")).toBeNull()
    expect(document.body.textContent).not.toMatch(/smp objective|smp milestone|\/smp/)
  })

  it("asks first, sends the objective it names, and shows the server's answer", async () => {
    const sent = backend()
    draw(<SmpActions />)

    fireEvent.click(await screen.findByRole("button", { name: "Complete Netherite scrap" }))
    const dialog = await screen.findByRole("alertdialog")
    expect(sent).toHaveLength(0)
    fireEvent.click(within(dialog).getByRole("button", { name: "Complete" }))

    await waitFor(() =>
      expect(sent).toEqual([{ url: "/api/smp/objective", body: { key: "netherite-scrap" } }]),
    )
    expect(await within(dialog).findByText("Objective closed.")).toBeTruthy()
  })

  it("unlocks the milestone it is drawn under", async () => {
    const sent = backend()
    draw(<SmpActions />)

    fireEvent.click(await screen.findByRole("button", { name: "Unlock" }))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Unlock" }))

    await waitFor(() =>
      expect(sent).toEqual([{ url: "/api/smp/milestone", body: { key: "frontier" } }]),
    )
  })

  it("says so when nobody picked the row up, rather than that it failed", async () => {
    backend({ row: { status: "EXPIRED" } })
    draw(<SmpActions />)

    fireEvent.click(await screen.findByRole("button", { name: "Unlock" }))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Unlock" }))

    expect(await within(dialog).findByText(/Nobody picked this up within two minutes/)).toBeTruthy()
  })
})

describe("HungerGamesActions", () => {
  it("cannot start a round when none is open", async () => {
    backend({ round: {} })
    draw(<HungerGamesActions />)

    expect(await screen.findByText("No round is open.")).toBeTruthy()
    expect((screen.getByRole("button", { name: "Start round" }) as HTMLButtonElement).disabled).toBe(true)
  })

  it("starts with a confirmation and no word to type", async () => {
    const sent = backend({ roundAfter: { state: "COUNTDOWN" } })
    draw(<HungerGamesActions />)

    expect(await screen.findByText("4 registered")).toBeTruthy()
    fireEvent.click(screen.getByRole("button", { name: "Start round" }))
    const dialog = await screen.findByRole("alertdialog")
    expect(within(dialog).queryByRole("textbox")).toBeNull()
    fireEvent.click(within(dialog).getByRole("button", { name: "Start" }))

    await waitFor(() => expect(sent).toEqual([{ url: "/api/hunger-games/start", body: {} }]))
    await within(dialog).findByText("Objective closed.")
    // The round moved on, so there is nothing to start anyway.
    await waitFor(() => expect(within(dialog).queryByRole("button", { name: "Start anyway" })).toBeNull())
  })

  it("offers the second step only after the server answered and the round is still open", async () => {
    const sent = backend({ row: { status: "DONE", result: "Only 4 of the recommended 8." } })
    draw(<HungerGamesActions />)

    await screen.findByText("4 registered")
    fireEvent.click(screen.getByRole("button", { name: "Start round" }))
    let dialog = await screen.findByRole("alertdialog")
    expect(within(dialog).queryByRole("button", { name: "Start anyway" })).toBeNull()
    fireEvent.click(within(dialog).getByRole("button", { name: "Start" }))

    fireEvent.click(await within(dialog).findByRole("button", { name: "Start anyway" }))
    dialog = await screen.findByRole("alertdialog")
    fireEvent.click(await within(dialog).findByRole("button", { name: "Start anyway" }))

    await waitFor(() =>
      expect(sent).toEqual([
        { url: "/api/hunger-games/start", body: {} },
        { url: "/api/hunger-games/start", body: { confirm: true } },
      ]),
    )
  })
})
