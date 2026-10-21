import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import { RowActions } from "@/components/steward/row-actions"

afterEach(cleanup)

describe("a row's actions", () => {
  it("fold into a ghost three-dot button as high as the row", () => {
    render(
      <RowActions menu label="Actions for Ally" actions={[{ key: "a", node: <button type="button">Grant</button> }]} />,
    )

    const trigger = screen.getByRole("button", { name: "Actions for Ally" })
    expect(trigger.dataset.variant).toBe("ghost")
    expect(trigger.className).toContain("size-row")
  })
})
