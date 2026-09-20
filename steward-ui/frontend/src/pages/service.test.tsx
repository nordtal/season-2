import type React from "react"
import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import { TooltipProvider } from "@/components/ui/tooltip"
import { ServiceHead } from "@/pages/service"

/**
 * steward/86, Till's review on 2026-09-18: the start page shows every player count correctly, and
 * the page of each Minecraft service shows none.
 *
 * The rule the start page already follows holds here too: **absent is not zero.** Only four
 * services carry a count at all, and one of those four carries none while proxy has not
 * written recently enough for the worker to trust the row - printing `0` there would turn "nobody
 * has said" into "nobody is on".
 */
const service = (over: Record<string, unknown> = {}) =>
  ({
    service: "smp",
    containerId: "abc123",
    image: "ghcr.io/nordtal/minecraft:latest",
    state: "running",
    status: "Up 3 hours (healthy)",
    hasConsole: true,
    drift: "NONE",
    health: "healthy",
    ...over,
  }) as never

/** The head draws tooltips, and Radix wants its provider above them. */
function draw(node: React.ReactNode) {
  return render(<TooltipProvider>{node}</TooltipProvider>)
}

afterEach(cleanup)

describe("ServiceHead - the player count on a Minecraft service's own page (steward/86)", () => {
  it("shows the count the row carries", () => {
    draw(<ServiceHead service={service({ players: 3 })} />)

    expect(screen.queryByText("Players")).not.toBeNull()
    expect(screen.queryByText("3")).not.toBeNull()
  })

  it("shows the field at all when the count is a real zero", () => {
    draw(<ServiceHead service={service({ players: 0 })} />)

    expect(screen.queryByText("Players")).not.toBeNull()
    expect(screen.queryByText("0")).not.toBeNull()
  })

  it("leaves the field out entirely for a service that carries no count", () => {
    draw(<ServiceHead service={service()} />)

    expect(screen.queryByText("Players")).toBeNull()
  })
})

/**
 * season-2-ops/125: a service that is down because somebody pressed Down and a service that is down
 * because it fell over are the same container to Docker - stopped, with an exit code. The whole
 * difference lives in `service_hold`, arrives on the row as `hold`, and this head is where a person
 * sees it. Absent is not false, the same rule `players` follows above.
 */
describe("ServiceHead - a service somebody is holding down (season-2-ops/125)", () => {
  it("says so when the row carries a hold", () => {
    draw(
      <ServiceHead
        service={service({
          state: "exited",
          status: "Exited (143) 4 minutes ago",
          hold: { since: "2026-09-19T10:00:00Z", by: "till (1)" },
        })}
      />,
    )

    // steward/134 merged the two badges into one: the state badge itself reads "held down" now,
    // rather than Docker's word in red with an explanation beside it.
    expect(screen.queryByText("held down")).not.toBeNull()
    expect(screen.queryByText("exited")).toBeNull()
  })

  it("says nothing of the sort about a service that merely stopped", () => {
    draw(<ServiceHead service={service({ state: "exited", status: "Exited (1) 4 minutes ago" })} />)

    expect(screen.queryByText("held down")).toBeNull()
    expect(screen.queryByText("exited")).not.toBeNull()
  })
})
