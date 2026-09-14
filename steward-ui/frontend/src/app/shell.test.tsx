import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { Shell } from "@/app/shell"

/**
 * Which of the three doors `/api/me` opens.
 *
 * There are exactly three answers and they used to be two. A 401 is the session being gone and is
 * the sign-in page. **Anything else is a fault**, and dressing it up as a missing session is worse
 * than useless: the only action the sign-in page offers - signing in again - cannot fix a 500 or a
 * 429, and the actual reason was nowhere on the screen. That is what `DoorIsStuck` is for; it
 * carries the same {@link Failure} every list uses, so it still names *which* of the three services
 * answered badly.
 *
 * These tests render the real `Shell`. They never reach the signed-in branch, which is deliberate -
 * that branch needs a router and a sidebar and would test neither of the two sentences above. What
 * is asserted instead is the one thing that must never be true: that a fault offers a sign-in
 * button, or that a missing session offers a retry button.
 *
 * `/api/me` is the one route the backend serves without a session (StewardUi.java excludes it from
 * the `before("/api/*")` filter), so in this deployment a 401 from it can only come from something
 * in front of Javalin. The branch is kept and tested anyway: a proxy that answers 401 is exactly
 * the case where an operator needs to be told to sign in rather than to press "Try again".
 */

function answer(status: number, body: unknown): Response {
  return new Response(body === undefined ? "" : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

let fetched: ReturnType<typeof vi.fn>

/** Retries off: `useMe` sets `retry: false` itself, and this keeps the client honest about it. */
function draw() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <Shell />
    </QueryClientProvider>,
  )
}

/** The sign-in page, identified by the one thing only it has. */
const signInButton = () => screen.queryByRole("link", { name: /Sign in with Discord/ })

/** The stuck door, identified by the sentence that says it is not a session problem. */
const stuckDoor = () => screen.queryByText(/not an expired session/)

beforeEach(() => {
  fetched = vi.fn()
  vi.stubGlobal("fetch", fetched)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("Shell - the answer that means the session is gone", () => {
  it("draws the sign-in page when /api/me says nobody is signed in", async () => {
    fetched.mockResolvedValue(answer(200, { signedIn: false, webauthn: "none" }))
    draw()

    await waitFor(() => expect(signInButton()).not.toBeNull())
    expect(stuckDoor()).toBeNull()
  })

  it("draws it for a 401 as well, which is the only status that means this", async () => {
    fetched.mockResolvedValue(answer(401, { error: "sign in first" }))
    draw()

    await waitFor(() => expect(signInButton()).not.toBeNull())
    expect(stuckDoor()).toBeNull()
  })

  it("accuses nobody at all while the answer is still on its way", async () => {
    // Neither door. A shell drawn first and replaced a moment later flashes a sidebar full of
    // pages that all answer 401, which reads as a broken interface rather than a missing session.
    fetched.mockImplementation(() => new Promise<Response>(() => {}))
    draw()

    await waitFor(() => expect(fetched).toHaveBeenCalled())
    expect(signInButton()).toBeNull()
    expect(stuckDoor()).toBeNull()
  })
})

describe("Shell - every other answer is a fault, not a missing session", () => {
  // 500 is the service itself, 502 is something it depends on, 429 is a rate limit and 403 is an
  // account that will never be allowed in. Signing in again fixes none of the four.
  const faults: Array<[number, string]> = [
    [500, "Internal error."],
    [502, "steward-worker is not answering."],
    [429, "Too many requests."],
    [403, "Nothing happens here without the admin role."],
  ]

  for (const [status, message] of faults) {
    it(`shows the stuck door and not the sign-in page for ${status}`, async () => {
      fetched.mockResolvedValue(answer(status, { error: message }))
      draw()

      await waitFor(() => expect(stuckDoor()).not.toBeNull())
      expect(signInButton()).toBeNull()
      // The reason is on the screen, which is the entire complaint the old behaviour answered.
      expect(screen.getByRole("alert").textContent).toContain(message)
      expect(screen.getByRole("alert").textContent).toContain(`HTTP ${status}`)
    })
  }

  it("shows it when the request never arrived anywhere", async () => {
    // `api()` turns a rejected fetch into an ApiError with status 0 - a stopped steward-ui, a
    // proxy in the way, a browser that is offline. Zero is not 401.
    fetched.mockRejectedValue(new TypeError("Failed to fetch"))
    draw()

    await waitFor(() => expect(stuckDoor()).not.toBeNull())
    expect(signInButton()).toBeNull()
    expect(screen.getByRole("alert").textContent).toContain("The interface cannot be reached.")
  })

  it("shows it for a failure that is not an ApiError at all", async () => {
    // 200 with a body that is not an object: `useMe` reads `me.csrf` off it and throws a
    // TypeError, so `error instanceof ApiError` is false. The `instanceof` half of the condition
    // is what catches this; a bare `error.isSignedOut` would have thrown reading the getter.
    fetched.mockResolvedValue(answer(200, null))
    draw()

    await waitFor(() => expect(stuckDoor()).not.toBeNull())
    expect(signInButton()).toBeNull()
  })

  it("offers to ask again, and asking again is a second request", async () => {
    // The one action that can help. A sign-in page would have offered the one that cannot.
    fetched.mockResolvedValue(answer(500, { error: "Internal error." }))
    draw()

    await waitFor(() => expect(stuckDoor()).not.toBeNull())
    expect(fetched).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole("button", { name: /Try again/ }))

    await waitFor(() => expect(fetched).toHaveBeenCalledTimes(2))
    expect(stuckDoor()).not.toBeNull()
  })

  it("keeps the shell out of the way even though the pages could report it themselves", async () => {
    // Not a matter of taste: the CSRF token arrives with this answer, so without it every write
    // in the interface is refused, one confusing page at a time.
    fetched.mockResolvedValue(answer(500, { error: "Internal error." }))
    draw()

    await waitFor(() => expect(stuckDoor()).not.toBeNull())
    expect(screen.queryByRole("navigation")).toBeNull()
    expect(screen.queryByRole("main")).toBeNull()
  })
})

/*
 * What is NOT here, and why: the signed-in branch.
 *
 * Past both refusals the Shell renders `<Outlet/>`, a sidebar and `useRouterState`, so it needs a
 * router - and a router needs a route tree, a memory history and a `window.matchMedia` jsdom does
 * not have. A test that renders it without one gets as far as the loading skeleton and then throws
 * asynchronously inside TanStack Router, which passes while asserting nothing. That the shell
 * appears for a signed-in operator is therefore still only known by looking at it.
 */
