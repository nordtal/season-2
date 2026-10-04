import { cleanup, render } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import { Change } from "@/pages/operations"

/** A change of a run's report: told in a message when no version names it, as the bot and the terminal render it. */

afterEach(cleanup)

describe("a change in a run's report", () => {
  it("renders a change told in a message in its words, not the key and not the placeholder", () => {
    const { container } = render(
      <Change change={{ artefact: "image", to: "-", state: "MOVING", told: { key: "report.image-outdated" } }} />,
    )
    expect(container.textContent).toBe("imageout of date")
  })

  it("renders a container made again from a pulled image through its argument", () => {
    const { container } = render(
      <Change
        change={{
          artefact: "container",
          to: "-",
          state: "MOVING",
          told: { key: "report.made-again", args: { pull: { kind: "choice", value: true } } },
        }}
      />,
    )
    expect(container.textContent).toBe("containermade again from a pulled image")
  })

  it("still reads a change stored in words by an earlier release", () => {
    const { container } = render(<Change change={{ artefact: "image", to: "out of date", state: "MOVING" }} />)
    expect(container.textContent).toContain("out of date")
  })
})
