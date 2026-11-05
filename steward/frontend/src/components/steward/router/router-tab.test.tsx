import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { Router, Run, RunStep, StepKind } from "@/lib/api"
import { asButton, asInput } from "@/lib/test-elements"
import { TooltipProvider } from "@/components/ui/tooltip"
import { RouterTab } from "@/components/steward/router/router-tab"
import { localInput } from "@/components/steward/router/launch-card"

/**
 * The Router tab in each state of its cards, and that each dialog sends its change only from its last step.
 */

vi.mock("@tanstack/react-router", () => ({
  Link: ({ children, className }: { children?: ReactNode; className?: string }) => (
    <a href="#run" className={className}>
      {children}
    </a>
  ),
}))

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

const at = (offset: number) => new Date(Date.now() + offset).toISOString()

function season(over: Partial<Router> = {}): Router {
  return {
    opening: "LAUNCHED",
    launch: at(-2 * DAY),
    maintenance: false,
    mode: "SMP",
    anchoredGrants: 14,
    ...over,
  }
}

function step(kind: StepKind, over: Partial<RunStep> = {}): RunStep {
  return {
    seq: 1,
    step: kind,
    started: at(-MINUTE),
    finished: at(-MINUTE),
    outcome: "DONE",
    lines: [],
    changes: [],
    players: [],
    imagesRemoved: [],
    saved: [],
    pruned: [],
    ...over,
  }
}

function run(over: Partial<Run> = {}): Run {
  return {
    id: 412,
    kind: "SWITCH_MODE",
    status: "RUNNING",
    actorKind: "PERSON",
    actorId: "123456789012345678",
    scope: [],
    requested: at(-3 * MINUTE),
    scheduledFor: at(-3 * MINUTE),
    moving: [],
    started: at(-2 * MINUTE),
    steps: [],
    ...over,
  }
}

function server(name: string, state: string, players = 0, over: Record<string, unknown> = {}) {
  return { service: name, containerId: name, state, hasConsole: true, drift: "UP_TO_DATE", players, ...over }
}

const SERVERS = [server("smp", "running", 21), server("hunger-games", "exited", 0, { inactiveMode: true })]

type Sent = { url: string; body: unknown }

function backend(router: Router, services: unknown[] = SERVERS) {
  const sent: Sent[] = []
  const fetched = vi.fn<(url: string, init?: { method?: string; body?: string }) => Promise<Response>>(
    async (url, init) => {
      if (init?.method === "POST") {
        sent.push({ url, body: JSON.parse(init.body ?? "{}") })
        if (url === "/api/season/switch") return json(200, { target: "HUNGER_GAMES", run: run() })
        return json(200, { previous: {}, current: {}, grants: 0, accounts: 0 })
      }
      if (url === "/api/season") return json(200, router)
      if (url === "/api/season/players") return json(200, { members: 21, admins: 2, withoutAccess: 7 })
      if (url === "/api/services") return json(200, { services, drift: { reached: true, unverifiable: [] } })
      if (url === "/api/updates/active") return json(200, {})
      if (url === "/api/people") return json(200, [])
      if (url === "/api/settings") return json(200, {})
      throw new Error(`the tab asked for ${url}, which this test did not expect`)
    },
  )
  vi.stubGlobal("fetch", fetched)
  return sent
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } })
}

function draw() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <TooltipProvider>
        <RouterTab />
      </TooltipProvider>
    </QueryClientProvider>,
  )
}

const button = (name: string | RegExp) => asButton(screen.getByRole("button", { name }))

async function press(name: string | RegExp) {
  const found = await screen.findByRole("button", { name })
  await waitFor(() => expect(asButton(found).disabled).toBe(false))
  fireEvent.click(found)
}

const dialog = () => screen.findByRole("alertdialog")

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("RouterTab - the launch card", () => {
  it("offers only to set a date while the season is closed", async () => {
    backend(season({ opening: "CLOSED", launch: undefined }))
    draw()
    expect(await screen.findByRole("button", { name: /Set launch date/ })).toBeTruthy()
    expect(screen.getByText("not set")).toBeTruthy()
    expect(screen.queryByRole("button", { name: /Launch now/ })).toBeNull()
    expect(screen.queryByRole("button", { name: /Close season/ })).toBeNull()
  })

  it("offers Launch now and Change during the countdown", async () => {
    backend(season({ opening: "COUNTDOWN", launch: at(16 * DAY) }))
    draw()
    expect(await screen.findByRole("button", { name: /Launch now/ })).toBeTruthy()
    expect(button(/Change/)).toBeTruthy()
    expect(screen.queryByRole("button", { name: /Close season/ })).toBeNull()
  })

  it("offers Change and Close season once launched", async () => {
    backend(season())
    draw()
    expect(await screen.findByRole("button", { name: /Close season/ })).toBeTruthy()
    expect(button(/Change/)).toBeTruthy()
    expect(screen.queryByRole("button", { name: /Launch now/ })).toBeNull()
  })

  it("launches now from its dialog, with the reason typed", async () => {
    const sent = backend(season({ opening: "COUNTDOWN", launch: at(16 * DAY) }))
    draw()
    await press(/Launch now/)
    const asked = await dialog()
    expect(within(asked).getByText("Launch the season now?")).toBeTruthy()
    fireEvent.change(within(asked).getByLabelText("Reason"), { target: { value: "early start" } })
    fireEvent.click(within(asked).getByRole("button", { name: "Launch now" }))
    await waitFor(() => expect(sent).toEqual([{ url: "/api/season/launch-now", body: { reason: "early start" } }]))
  })

  it("closes the season from its dialog, which names the members it moves", async () => {
    const sent = backend(season())
    draw()
    await press(/Close season/)
    const asked = await dialog()
    expect(within(asked).getByText("Close the season?")).toBeTruthy()
    expect(await within(asked).findByText("21 members move to the closed screen.")).toBeTruthy()
    fireEvent.click(within(asked).getByRole("button", { name: "Close season" }))
    await waitFor(() => expect(sent).toEqual([{ url: "/api/season/close", body: {} }]))
  })
})

describe("RouterTab - the date editor", () => {
  it("sets a date in two steps and sends it only from the second", async () => {
    const sent = backend(season({ opening: "CLOSED", launch: undefined }))
    draw()
    await press(/Set launch date/)
    const target = new Date(Date.now() + 16 * DAY)
    target.setSeconds(0, 0)
    fireEvent.change(screen.getByLabelText("Launch date"), { target: { value: localInput(target) } })
    fireEvent.click(button("Save"))

    const first = await dialog()
    expect(within(first).getByText("Set the launch date?")).toBeTruthy()
    expect(within(first).getByText("Step 1 of 2")).toBeTruthy()
    fireEvent.click(within(first).getByRole("button", { name: "Continue" }))
    expect(sent).toEqual([])

    const second = await dialog()
    expect(within(second).getByText("Step 2 of 2")).toBeTruthy()
    expect(within(second).getByText("The season opens by itself")).toBeTruthy()
    expect(within(second).getByRole("button", { name: "Back" })).toBeTruthy()
    fireEvent.click(within(second).getByRole("button", { name: /^Open on / }))
    await waitFor(() => expect(sent).toEqual([{ url: "/api/season/launch", body: { at: target.toISOString() } }]))
  })

  it("refuses a date in the past", async () => {
    backend(season({ opening: "CLOSED", launch: undefined }))
    draw()
    await press(/Set launch date/)
    fireEvent.change(screen.getByLabelText("Launch date"), {
      target: { value: localInput(new Date(Date.now() - DAY)) },
    })
    expect(screen.getByText(/^Not in the past\. Earliest is now/)).toBeTruthy()
    expect(button("Save").disabled).toBe(true)
  })

  it("says that a new date after the launch closes the season until then", async () => {
    backend(season())
    draw()
    await press(/Change/)
    expect(screen.getByText("Closes the season until then.")).toBeTruthy()
    fireEvent.click(button("Save"))
    const first = await dialog()
    expect(within(first).getByText(/^Close the season until .+\?$/)).toBeTruthy()
    expect(await within(first).findByText("21 members move to the countdown screen.")).toBeTruthy()
    fireEvent.click(within(first).getByRole("button", { name: "Continue" }))
    const second = await dialog()
    expect(within(second).getByRole("button", { name: /^Close until / })).toBeTruthy()
  })

  it("starts over at its reason when opened again", async () => {
    backend(season({ opening: "COUNTDOWN", launch: at(16 * DAY) }))
    draw()
    await press(/Change/)
    fireEvent.click(button("Save"))
    const first = await dialog()
    fireEvent.change(within(first).getByLabelText("Reason"), { target: { value: "moved by a week" } })
    fireEvent.click(within(first).getByRole("button", { name: "Cancel" }))
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull())
    fireEvent.click(button("Save"))
    expect(asInput(within(await dialog()).getByLabelText("Reason")).value).toBe("")
  })
})

describe("RouterTab - the maintenance card", () => {
  it("starts maintenance without a key, and says what it does to members", async () => {
    const sent = backend(season())
    draw()
    await press(/Start maintenance/)
    const asked = await dialog()
    expect(await within(asked).findByText("21 members go to the waiting room. Admins stay.")).toBeTruthy()
    expect(within(asked).queryByText(/security key next/)).toBeNull()
    fireEvent.click(within(asked).getByRole("button", { name: "Start maintenance" }))
    await waitFor(() => expect(sent).toEqual([{ url: "/api/season/maintenance", body: { on: true } }]))
  })

  it("shows since when and how many wait while it is on, and ends it", async () => {
    const sent = backend(
      season({ maintenance: true, lastMaintenance: { on: at(-12 * MINUTE), byKind: "HOST", byId: "" } }),
      [...SERVERS, server("limbo", "running", 8)],
    )
    draw()
    expect(await screen.findByText("Since")).toBeTruthy()
    expect(screen.getByText("Waiting room")).toBeTruthy()
    await press(/End maintenance/)
    const asked = await dialog()
    expect(within(asked).getByText("8 members waiting go on through the gate.")).toBeTruthy()
    fireEvent.click(within(asked).getByRole("button", { name: "End maintenance" }))
    await waitFor(() => expect(sent).toEqual([{ url: "/api/season/maintenance", body: { on: false } }]))
  })

  it("has no effect before the launch, which its badge says", async () => {
    backend(season({ opening: "COUNTDOWN", launch: at(DAY) }))
    draw()
    expect(await screen.findByText("no effect yet")).toBeTruthy()
    expect(screen.getByText("never")).toBeTruthy()
  })
})

describe("RouterTab - the game mode card", () => {
  it("shows the inactive server resting, with Start and the switch", async () => {
    backend(season())
    draw()
    expect(await screen.findByText("resting")).toBeTruthy()
    expect(screen.getByText("active")).toBeTruthy()
    expect(await screen.findByRole("button", { name: /^Start$/ })).toBeTruthy()
    expect(screen.queryByRole("button", { name: /^Stop$/ })).toBeNull()
    expect(button(/^Switch/)).toBeTruthy()
  })

  it("shows a server started by hand with Stop instead", async () => {
    backend(season({ handStart: { service: "hunger-games", byKind: "HOST", byId: "", at: at(-MINUTE) } }), [
      server("smp", "running", 21),
      server("hunger-games", "running", 2, { inactiveMode: true }),
    ])
    draw()
    expect(await screen.findByText("started by hand")).toBeTruthy()
    expect(screen.getByText("2 admins on it")).toBeTruthy()
    expect(await screen.findByRole("button", { name: /^Stop$/ })).toBeTruthy()
  })

  it("asks for the switch and sends its target", async () => {
    const sent = backend(season())
    draw()
    await press(/^Switch/)
    const asked = await dialog()
    expect(within(asked).getByText("Switch to Hunger Games?")).toBeTruthy()
    expect(within(asked).getByText(/security key next/)).toBeTruthy()
    fireEvent.click(within(asked).getByRole("button", { name: "Switch" }))
    await waitFor(() => expect(sent).toEqual([{ url: "/api/season/switch", body: { target: "HUNGER_GAMES" } }]))
  })
})

describe("RouterTab - the switch run card", () => {
  it("shows a queued switch with Cancel and hides the switch", async () => {
    backend(
      season({
        mode: "SMP",
        lastSwitch: { target: "SMP", run: run({ status: "PENDING", started: undefined, scheduledFor: at(HOUR) }) },
      }),
    )
    draw()
    expect(await screen.findByText("waiting")).toBeTruthy()
    expect(await screen.findByRole("button", { name: /Cancel/ })).toBeTruthy()
    expect(screen.queryByRole("button", { name: /^Switch/ })).toBeNull()
  })

  it("draws a running switch on its four stages", async () => {
    const moved = { from: "hunger-games", to: "limbo", moved: 31, returned: 0 }
    backend(
      season({
        lastSwitch: {
          target: "SMP",
          run: run({
            steps: [
              step("PLAYERS_MOVED", { players: [moved] }),
              step("STOP", { service: "hunger-games" }),
              step("START", { service: "smp", finished: undefined, outcome: undefined }),
            ],
          }),
        },
      }),
    )
    draw()
    expect(await screen.findByText("Mode switch")).toBeTruthy()
    for (const name of ["Players to the waiting room", "Stop Hunger Games", "Start SMP", "Players to SMP"]) {
      expect(screen.getByText(name)).toBeTruthy()
    }
    expect(screen.getByText("31 / 31 arrived")).toBeTruthy()
    expect(screen.getByText(/^for /)).toBeTruthy()
  })

  it("offers Try again and the way back after a failed switch", async () => {
    const sent = backend(
      season({
        lastSwitch: {
          target: "SMP",
          run: run({
            status: "FAILED",
            finished: at(0),
            steps: [step("START", { service: "smp", outcome: "FAILED", reason: "NOT_HEALTHY" })],
          }),
        },
      }),
    )
    draw()
    expect(await screen.findByText("failed")).toBeTruthy()
    expect(screen.getByText("Open the run log")).toBeTruthy()
    await press(/Back to Hunger Games/)
    const asked = await dialog()
    fireEvent.click(within(asked).getByRole("button", { name: "Switch" }))
    await waitFor(() => expect(sent).toEqual([{ url: "/api/season/switch", body: { target: "HUNGER_GAMES" } }]))
    expect(button(/Try again/)).toBeTruthy()
  })

  it("sums up the last switch once it is done", async () => {
    backend(
      season({
        lastSwitch: {
          target: "SMP",
          run: run({
            status: "DONE",
            finished: at(0),
            steps: [step("PLAYERS_BACK", { players: [{ from: "limbo", to: "smp", moved: 31, returned: 24 }] })],
          }),
        },
      }),
    )
    draw()
    expect(await screen.findByText("Last switch")).toBeTruthy()
    expect(screen.getByText("24 to SMP")).toBeTruthy()
    expect(screen.getByText("7 without access")).toBeTruthy()
  })
})
