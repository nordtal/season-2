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

  it("renders a backup's size and time from their values, the size a message of its own", () => {
    const size = {
      key: "report.size",
      args: { amount: { kind: "number", value: 1.2 }, unit: { kind: "choice", value: "mib" } },
    } as const
    const { container } = render(
      <Change
        change={{
          artefact: "backup",
          to: "-",
          state: "MOVING",
          told: {
            key: "report.saved",
            args: { size: { kind: "message", value: size }, took: { kind: "duration", value: 72 } },
          },
        }}
      />,
    )
    expect(container.textContent).toBe("backupsaved 1.2 MiB in 1m 12s")
  })

  it("still reads a change stored in words by an earlier release", () => {
    const { container } = render(<Change change={{ artefact: "image", to: "out of date", state: "MOVING" }} />)
    expect(container.textContent).toContain("out of date")
  })
})
