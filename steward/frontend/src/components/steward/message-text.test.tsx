import { cleanup, render } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import { MessageText } from "@/components/steward/message-text"

afterEach(cleanup)

describe("MessageText", () => {
  it("sets a code span in the mono face and drops its backticks", () => {
    const { container } = render(
      <p>
        <MessageText text="`https://pack.nordtal.eu/nordtal.zip` does not answer." />
      </p>,
    )
    expect(container.textContent).toBe("https://pack.nordtal.eu/nordtal.zip does not answer.")
    const code = container.querySelector("code")
    expect(code?.textContent).toBe("https://pack.nordtal.eu/nordtal.zip")
    expect(code?.className).toContain("font-mono")
  })

  it("leaves a text without a pair of backticks as it is", () => {
    const { container } = render(
      <p>
        <MessageText text="One ` alone stays." />
      </p>,
    )
    expect(container.textContent).toBe("One ` alone stays.")
    expect(container.querySelector("code")).toBeNull()
  })
})
