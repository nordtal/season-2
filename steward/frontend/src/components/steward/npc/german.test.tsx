import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { useState } from "react"
import { afterEach, describe, expect, it } from "vitest"

import { German, GermanProvider } from "@/components/steward/npc/german"

function Opened({ initially, missing }: { initially: string[]; missing: boolean }) {
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set(initially))
  const toggle = (key: string, shown: boolean) =>
    setOpen((before) => {
      const after = new Set(before)
      if (shown) after.add(key)
      else after.delete(key)
      return after
    })
  return (
    <GermanProvider value={{ open, toggle }}>
      <German id="name" missing={missing}>
        <input aria-label="German name" />
      </German>
    </GermanProvider>
  )
}

describe("German under its English text", () => {
  afterEach(cleanup)

  it("starts closed and says when the German text is missing", () => {
    render(<Opened initially={[]} missing />)
    expect(screen.getByRole("button", { name: "German" }).getAttribute("aria-expanded")).toBe("false")
    expect(screen.getByText("No German text")).toBeTruthy()
    expect(screen.queryByLabelText("German name")).toBeNull()
  })

  it("opens on a click and hides the mark while open", () => {
    render(<Opened initially={[]} missing />)
    fireEvent.click(screen.getByRole("button", { name: "German" }))
    expect(screen.getByLabelText("German name")).toBeTruthy()
    expect(screen.queryByText("No German text")).toBeNull()
  })

  it("is open when the editor opened it, as a refused publish does", () => {
    render(<Opened initially={["name"]} missing={false} />)
    expect(screen.getByLabelText("German name")).toBeTruthy()
  })
})
