import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import { OnlineLine, type Online } from "@/components/steward/online"

/**
 * The one claim the heading makes that nothing else on the start page can check (steward/64).
 *
 * `OnlineLine` is the variant that went onto the page, and its premise is that the overflow circle
 * is the *general* case rather than the leftovers: Steward knows how many people are in the game
 * and not which people, so with no roster at all the whole count stands in that circle. A test that
 * only ever ran with three faces would never see that state, which is the only state the product is
 * actually in today.
 *
 * The page's own test file covers the number and the dash through a real fetch; what is here is the
 * circle, because the page cannot render one until steward/111 writes a roster down.
 */
const queryClient = () => new QueryClient({ defaultOptions: { queries: { retry: false } } })

function draw(online: Online) {
  return render(
    <QueryClientProvider client={queryClient()}>
      <OnlineLine online={online} />
    </QueryClientProvider>,
  )
}

const online = (over: Partial<Online> = {}): Online => ({
  total: undefined,
  servers: [],
  roster: [],
  pending: false,
  ...over,
})

afterEach(cleanup)

describe("OnlineLine - the overflow circle carries the whole count while there is no roster", () => {
  it("reads +7 beside 7 players online, and names itself for a screen reader", () => {
    draw(online({ total: 7 }))

    expect(screen.getByLabelText("7 more in the game").textContent).toBe("+7")
    expect(screen.getByText("players online").closest("p")?.textContent).toBe("7players online")
  })

  it("draws no circle at all when nobody is in the game", () => {
    draw(online({ total: 0 }))

    expect(screen.queryByLabelText(/more in the game/)).toBeNull()
    expect(screen.getByText("players online").closest("p")?.textContent).toBe("0players online")
  })

  it("draws no circle, and no zero, when no row carried a count", () => {
    draw(online({ total: undefined }))

    expect(screen.queryByLabelText(/more in the game/)).toBeNull()
    expect(screen.getByText("players online").closest("p")?.textContent).toBe("–players online")
  })

  it("says player, not players, for the one person on", () => {
    draw(online({ total: 1 }))

    expect(screen.getByText("player online")).toBeTruthy()
  })
})
