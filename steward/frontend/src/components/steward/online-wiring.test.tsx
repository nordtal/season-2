import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { Service } from "@/lib/api"
import { useOnline } from "@/components/steward/online"
import { useServices } from "@/lib/queries"
import { queryResult } from "@/lib/query-fixtures"

/**
 * Being the only player online shows a face, not just `+1`.
 *
 * `pages/overview.tsx` must pass the `proxy` row's roster to `useOnline`, whose default is `[]`.
 */
vi.mock("@/lib/queries", () => ({ useServices: vi.fn<typeof useServices>(), useAvatarBaseUrl: () => undefined }))

function Probe() {
  const online = useOnline()
  return (
    <>
      <span data-testid="total">{String(online.total)}</span>
      <span data-testid="names">{online.roster.map((player) => player.name).join(",")}</span>
    </>
  )
}

function row(over: Partial<Service> & { service: string }): Service {
  return {
    containerId: "abc",
    image: "ghcr.io/nordtal/smp:1.4.0",
    state: "running",
    status: "Up 3 hours (healthy)",
    hasConsole: true,
    drift: "UP_TO_DATE",
    ...over,
  }
}

function services(rows: (Partial<Service> & { service: string })[]) {
  vi.mocked(useServices).mockReturnValue(
    queryResult({
      services: rows.map(row),
      drift: { checkedAt: "2026-09-15T00:00:00Z", reached: true, unverifiable: [] },
    }),
  )
}

afterEach(cleanup)

describe("useOnline - the roster comes from the same row the total does", () => {
  it("hands on the people proxy wrote down", () => {
    services([
      {
        service: "proxy",
        players: 1,
        roster: [{ uuid: "0a1b2c3d-4e5f-6a7b-8c9d-0e1f2a3b4c5d", name: "hmtill" }],
      },
    ])

    render(<Probe />)

    expect(screen.getByTestId("total").textContent).toBe("1")
    expect(screen.getByTestId("names").textContent).toBe("hmtill")
  })

  it("keeps an empty list when the row carries no roster at all - absence, not a guess", () => {
    services([{ service: "proxy", players: 4 }])

    render(<Probe />)

    expect(screen.getByTestId("total").textContent).toBe("4")
    expect(screen.getByTestId("names").textContent).toBe("")
  })
})
