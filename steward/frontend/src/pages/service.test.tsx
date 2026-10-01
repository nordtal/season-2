import type React from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { Service } from "@/lib/api"
import { TooltipProvider } from "@/components/ui/tooltip"
import { ServiceHead } from "@/pages/service"

const service = (over: Partial<Service> = {}): Service => ({
  service: "smp",
  containerId: "abc123",
  image: "ghcr.io/nordtal/minecraft:latest",
  digests: ["ghcr.io/nordtal/minecraft@sha256:b0d5cefd9e4a"],
  state: "running",
  status: "Up 3 hours (healthy)",
  hasConsole: true,
  drift: "NONE",
  health: "healthy",
  ...over,
})

/** The head draws tooltips and reads two metric series. */
function draw(node: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <TooltipProvider>{node}</TooltipProvider>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response(JSON.stringify({ points: [] }), { status: 200 })),
  )
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

/** One flat row: State with the uptime under it, then CPU, RAM and Disk. */
describe("ServiceHead - the number row", () => {
  it("draws State, CPU and RAM and nothing of what was dropped", () => {
    draw(<ServiceHead name="smp" service={service({ players: 3, cpuPercent: 12, memoryBytes: 2.9e9 })} />)

    for (const label of ["State", "CPU", "RAM"]) expect(screen.queryByText(label)).not.toBeNull()
    for (const gone of ["Players", "Image", "Console", "Uptime", "Up 3 hours (healthy)"]) {
      expect(screen.queryByText(gone)).toBeNull()
    }
    expect(screen.queryByText(/ghcr\.io/)).toBeNull()
    expect(screen.queryByText(/share of the host/)).toBeNull()
  })

  it("draws Disk when steward measured one, and says how old an old one is", () => {
    const old = new Date(Date.now() - 4 * 60 * 1000).toISOString()
    draw(<ServiceHead name="smp" service={service({ diskBytes: 564e6, diskMeasuredAt: old })} />)

    expect(screen.queryByText("Disk")).not.toBeNull()
    expect(screen.queryByText(/^564(\.0)? MB$/)).not.toBeNull()
    expect(screen.queryByText(/4 min/)).not.toBeNull()
  })

  it("says nothing about the age of a fresh measurement", () => {
    const fresh = new Date(Date.now() - 30 * 1000).toISOString()
    draw(<ServiceHead name="smp" service={service({ diskBytes: 564e6, diskMeasuredAt: fresh })} />)

    expect(screen.queryByText(/^564(\.0)? MB$/)).not.toBeNull()
    expect(screen.queryByText(/ago/)).toBeNull()
  })

  it("leaves Disk out for a service without a volume, rather than showing zero", () => {
    draw(<ServiceHead name="postgres" service={service({ service: "postgres" })} />)

    expect(screen.queryByText("Disk")).toBeNull()
  })
})

/** Held down and crashed look the same to Docker; only `hold` tells them apart, and absent is not false. */
describe("ServiceHead - a service somebody is holding down", () => {
  it("says so when the row carries a hold", () => {
    draw(
      <ServiceHead
        name="smp"
        service={service({
          state: "exited",
          status: "Exited (143) 4 minutes ago",
          hold: { since: "2026-09-19T10:00:00Z" },
        })}
      />,
    )

    /** One badge reading "held down", not Docker's word in red with an explanation beside it. */
    expect(screen.queryByText("held down")).not.toBeNull()
    expect(screen.queryByText("exited")).toBeNull()
  })

  it("says nothing of the sort about a service that merely stopped", () => {
    draw(<ServiceHead name="smp" service={service({ state: "exited", status: "Exited (1) 4 minutes ago" })} />)

    expect(screen.queryByText("held down")).toBeNull()
    expect(screen.queryByText("exited")).not.toBeNull()
  })
})
