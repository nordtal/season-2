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
 * services carry a count at all, and one of those four carries none while network-control has not
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
