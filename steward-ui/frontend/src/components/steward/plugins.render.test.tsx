import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { ServicePlugins } from "@/components/steward/plugins"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * The plugins tab: three lists by where a plugin comes from, the update check's answer on each row,
 * and a check button that only reads.
 */

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } })
}

const PLUGINS = {
  service: "smp",
  loader: "paper",
  gameVersion: "26.2",
  mounted: true,
  plugins: [
    { name: "SMP", running: true, removable: false, fileName: "smp-0.9.4.jar", group: "nordtal", rank: 1 },
    {
      name: "packetevents",
      running: true,
      removable: false,
      fileName: "packetevents-spigot-2.13.0.jar",
      projectId: "HYKaKraK",
      pageUrl: "https://modrinth.com/plugin/packetevents",
      group: "preinstalled",
    },
    {
      name: "Chunky",
      running: true,
      removable: false,
      fileName: "Chunky-Bukkit-1.5.3.jar",
      projectId: "fALzjamp",
      pageUrl: "https://modrinth.com/plugin/chunky",
      group: "preinstalled",
    },
    {
      name: "Display Tags",
      running: true,
      removable: false,
      fileName: "papermc-display-tags-2.2.0.jar",
      group: "nordtal",
      rank: 0,
    },
    {
      name: "CoreProtect",
      running: false,
      removable: false,
      artifact: "coreprotect",
      projectId: "Lu3KuzdV",
      iconUrl: "https://cdn.modrinth.com/data/Lu3KuzdV/icon.png",
      pageUrl: "https://modrinth.com/plugin/coreprotect",
      group: "preinstalled",
    },
    {
      name: "JourneyMap",
      running: true,
      removable: true,
      fileName: "journeymap-1.0.jar",
      artifact: "journeymap",
      projectId: "lfHFW1mp",
      dataFolder: "journeymap",
      group: "added",
    },
  ],
}

const AVAILABLE = {
  checkedAt: "2026-09-24T00:00:00Z",
  resolvedAt: "2026-09-24T00:00:00Z",
  seasonPrerelease: false,
  hasWork: true,
  hasFailures: false,
  unclaimed: [],
  notes: [],
  changes: [
    {
      service: "smp",
      artifact: "packetevents",
      status: "OUTDATED",
      work: true,
      failure: false,
      installed: "packetevents-spigot-2.13.0.jar",
      fileName: "packetevents-spigot-2.14.0.jar",
    },
    {
      service: "smp",
      artifact: "chunky",
      status: "UP_TO_DATE",
      work: false,
      failure: false,
      installed: "Chunky-Bukkit-1.5.3.jar",
      fileName: "Chunky-Bukkit-1.5.3.jar",
    },
    {
      service: "smp",
      artifact: "coreprotect",
      status: "MISSING",
      work: true,
      failure: false,
      version: "24.1",
      fileName: "CoreProtect-CE-24.1.jar",
    },
    { service: "smp", artifact: "smp", status: "UNRESOLVED", work: false, failure: true, installed: "smp-0.9.4.jar" },
    {
      service: "limbo",
      artifact: "chunky",
      status: "UNSUPPORTED",
      work: false,
      failure: false,
      installed: "Chunky-Bukkit-1.5.3.jar",
    },
  ],
}

function backend(available: () => Response) {
  return vi.fn(async (url: string) => {
    if (url === "/api/services/smp/plugins") return json(200, PLUGINS)
    if (url.startsWith("/api/updates/available")) return available()
    return json(404, { error: `not stubbed: ${url}` })
  })
}

function draw() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <TooltipProvider>
        <ServicePlugins service="smp" />
      </TooltipProvider>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("ServicePlugins", () => {
  it("draws Nordtal, Preinstalled and Added in that order, each alphabetical", async () => {
    vi.stubGlobal(
      "fetch",
      backend(() => json(200, AVAILABLE)),
    )
    draw()

    await screen.findByText("JourneyMap")
    const headings = screen.getAllByRole("heading").map((it) => it.textContent)
    expect(headings).toEqual(["Nordtal", "Preinstalled", "Added"])

    const namesIn = (heading: string) =>
      within(screen.getByRole("heading", { name: heading }).parentElement!)
        .getAllByRole("listitem")
        .map((it) => it.querySelector(".font-medium")?.textContent)
    // The name-tag fork is Nordtal's own and leads that list; the rest is alphabetical.
    expect(namesIn("Nordtal")).toEqual(["Display Tags", "SMP"])
    expect(namesIn("Preinstalled")).toEqual(["Chunky", "CoreProtect", "packetevents"])
  })

  it("puts the update check's answer on the row it belongs to", async () => {
    vi.stubGlobal(
      "fetch",
      backend(() => json(200, AVAILABLE)),
    )
    draw()

    expect(await screen.findByText("2.13.0 → 2.14.0")).toBeTruthy()
    expect(screen.getAllByText("up to date")).toHaveLength(1)
    // Not on the disk: said as exactly that, with the picture and name Modrinth gives it.
    const coreprotect = screen.getByText("CoreProtect").closest("li")!
    expect(within(coreprotect).getByText("Not installed")).toBeTruthy()
    expect(coreprotect.querySelector("img")).toBeTruthy()
    expect(screen.queryByText(/pre-booked/i)).toBeNull()
  })

  it("says there is no build when nothing resolves for a plugin that is not installed", async () => {
    const unsupported = {
      ...AVAILABLE,
      changes: AVAILABLE.changes.map((change) =>
        change.artifact === "coreprotect" ? { ...change, status: "UNSUPPORTED", work: false } : change,
      ),
    }
    vi.stubGlobal(
      "fetch",
      backend(() => json(200, unsupported)),
    )
    draw()

    const coreprotect = (await screen.findByText("CoreProtect")).closest("li")!
    expect(within(coreprotect).getByText("No 26.2 build")).toBeTruthy()
  })

  it("offers removal only for what somebody added, and Modrinth only for Modrinth plugins", async () => {
    vi.stubGlobal(
      "fetch",
      backend(() => json(200, AVAILABLE)),
    )
    draw()

    await screen.findByText("JourneyMap")
    expect(screen.getAllByRole("button", { name: /^Remove / }).map((it) => it.getAttribute("aria-label"))).toEqual([
      "Remove JourneyMap",
    ])
    expect(screen.getAllByRole("link").map((it) => it.getAttribute("aria-label"))).toEqual([
      "Chunky on Modrinth",
      "CoreProtect on Modrinth",
      "packetevents on Modrinth",
    ])
  })

  it("says nothing about updates when they cannot be checked", async () => {
    vi.stubGlobal(
      "fetch",
      backend(() => json(503, { error: "no GitHub" })),
    )
    draw()

    expect(await screen.findByText("Updates can't be checked right now.")).toBeTruthy()
    await screen.findByText("JourneyMap")
    expect(screen.queryByText("up to date")).toBeNull()
    expect(screen.queryByText(/→/)).toBeNull()
  })

  it("takes the old answer off the rows when a check fails", async () => {
    let calls = 0
    vi.stubGlobal(
      "fetch",
      backend(() => (++calls === 1 ? json(200, AVAILABLE) : json(503, { error: "no GitHub" }))),
    )
    draw()

    await screen.findByText("up to date")
    fireEvent.click(screen.getByRole("button", { name: "Check for updates" }))

    expect(await screen.findByText("Updates can't be checked right now.")).toBeTruthy()
    expect(screen.queryByText("up to date")).toBeNull()
  })

  it("checks for updates by asking again, and writes nothing", async () => {
    const fetch = backend(() => json(200, AVAILABLE))
    vi.stubGlobal("fetch", fetch)
    draw()

    await screen.findByText("up to date")
    fireEvent.click(screen.getByRole("button", { name: "Check for updates" }))

    await waitFor(() => expect(fetch.mock.calls.some(([url]) => url === "/api/updates/available?refresh")).toBe(true))
    for (const call of fetch.mock.calls as unknown as [string, RequestInit | undefined][]) {
      expect(call[1]?.method ?? "GET").toBe("GET")
    }
  })
})
