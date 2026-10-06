import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import { DriftBadge } from "@/components/steward/status"
import { DriftMark } from "@/components/steward/network/node"
import { TooltipProvider } from "@/components/ui/tooltip"

/** A service running something built on the host says so, whatever the registry thinks of its image. */

afterEach(cleanup)

describe("a local build", () => {
  it("is the badge's word even when the image is the release's", () => {
    render(
      <TooltipProvider>
        <DriftBadge drift="UP_TO_DATE" image="ghcr.io/nordtal/smp:0.17.0" localBuild={{ jars: ["smp-0.17.0.jar"] }} />
      </TooltipProvider>,
    )
    expect(screen.getByText("local build")).toBeTruthy()
  })

  it("names its jars on the network's mark", () => {
    render(<DriftMark drift="UP_TO_DATE" localBuild={{ jars: ["smp-0.17.0.jar"] }} />)
    expect(screen.getByRole("img", { name: "local build: smp-0.17.0.jar" })).toBeTruthy()
  })

  it("is not drawn for a service on the release", () => {
    const { container } = render(<DriftMark drift="UP_TO_DATE" />)
    expect(container.innerHTML).toBe("")
  })
})
