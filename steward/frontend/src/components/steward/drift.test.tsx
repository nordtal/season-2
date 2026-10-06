import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
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
    render(
      <TooltipProvider>
        <DriftMark drift="UP_TO_DATE" localBuild={{ jars: ["smp-0.17.0.jar"] }} />
      </TooltipProvider>,
    )
    expect(screen.getByRole("img", { name: "local build: smp-0.17.0.jar" })).toBeTruthy()
  })

  it("is not drawn for a service on the release", () => {
    const { container } = render(<DriftMark drift="UP_TO_DATE" />)
    expect(container.innerHTML).toBe("")
  })
})

/** What a finger does to an element: pointer events first, then the compatibility mouse events, focus, and the click. */
function tap(element: HTMLElement) {
  fireEvent.pointerDown(element, { pointerType: "touch" })
  fireEvent.pointerUp(element, { pointerType: "touch" })
  fireEvent.mouseDown(element)
  element.focus()
  fireEvent.mouseUp(element)
  fireEvent.click(element)
}

function drawBadge() {
  return render(
    <TooltipProvider>
      <DriftBadge drift="OUTDATED" image="ghcr.io/nordtal/smp:0.17.0" />
      <DriftBadge drift="UP_TO_DATE" image="ghcr.io/nordtal/smp:0.17.0" localBuild={{ jars: ["smp-0.17.0.jar"] }} />
    </TooltipProvider>,
  )
}

describe("on a touch screen, where nothing hovers", () => {
  it("a tap on a badge opens its tip and a second tap closes it", async () => {
    drawBadge()

    tap(screen.getByText("local build"))
    expect(await screen.findAllByText("smp-0.17.0.jar")).not.toHaveLength(0)

    tap(screen.getByText("local build"))
    await waitFor(() => expect(screen.queryByText("smp-0.17.0.jar")).toBeNull())
  })

  it("a tap elsewhere closes it, and a tap on another badge opens that one's tip instead", async () => {
    drawBadge()
    tap(screen.getByText("local build"))
    await screen.findAllByText("smp-0.17.0.jar")

    tap(screen.getByText("outdated"))

    await screen.findAllByText(/out of date/i)
    await waitFor(() => expect(screen.queryByText("smp-0.17.0.jar")).toBeNull())
  })

  it("a tap on the network's mark opens the same tip as the badge", async () => {
    render(
      <TooltipProvider>
        <DriftMark drift="UP_TO_DATE" localBuild={{ jars: ["smp-0.17.0.jar"] }} />
      </TooltipProvider>,
    )

    tap(screen.getByRole("img", { name: /local build/ }))

    expect((await screen.findAllByText("smp-0.17.0.jar")).length).toBeGreaterThan(0)
  })
})
