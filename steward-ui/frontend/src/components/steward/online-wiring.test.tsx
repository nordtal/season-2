import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { useOnline } from "@/components/steward/online"
import { useServices } from "@/lib/queries"

/**
 * steward/111, Till's own observation: **he was the only player online and saw `+1`, not his face.**
 *
 * The roster was never the problem - proxy writes it every ten seconds and the worker puts
 * it on the `proxy` row as `roster`. The interface simply never read it: `useOnline` took
 * the list as a parameter defaulting to `[]`, and its one caller (`pages/overview.tsx`) called it
 * with nothing. So the stack always had a count and never a face, for everybody, always.
 */
vi.mock("@/lib/queries", () => ({ useServices: vi.fn(), useAvatarBaseUrl: () => undefined }))

function Probe() {
  const online = useOnline()
  return (
    <>
      <span data-testid="total">{String(online.total)}</span>
      <span data-testid="names">{online.roster.map((player) => player.name).join(",")}</span>
    </>
  )
}

function services(rows: unknown[]) {
  vi.mocked(useServices).mockReturnValue({ data: { services: rows }, isPending: false } as never)
}

afterEach(cleanup)

describe("useOnline - the roster comes from the same row the total does (steward/111)", () => {
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
