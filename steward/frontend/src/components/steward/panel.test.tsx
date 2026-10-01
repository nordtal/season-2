import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import { Panel } from "@/components/steward/panel"

afterEach(cleanup)

describe("Panel", () => {
  it("prints the title as a heading and renders its children", () => {
    render(
      <Panel title="Season">
        <p>SMP-Start: 12 September</p>
      </Panel>,
    )

    expect(screen.getByRole("heading", { name: "Season" })).toBeTruthy()
    expect(screen.getByText("SMP-Start: 12 September")).toBeTruthy()
  })
})
