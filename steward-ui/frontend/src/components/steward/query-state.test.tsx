import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import { QueryState, TRANSIENT_GRACE_MS, transient } from "@/components/steward/query-state"
import { ApiError } from "@/lib/api"

/**
 * A disabled query must not look like a running one, though TanStack reports it `isPending` for ever.
 *
 * `fetchStatus` `"idle"` beside `isPending` is what tells them apart.
 */

afterEach(cleanup)

/** The shape a child is asked for: one expression, called with the data and without it. */
const row = (data: string | undefined) => <span>{data ?? "…"}</span>

describe("QueryState", () => {
  it("draws the waiting shape while a query is actually running", () => {
    render(
      <QueryState query={{ data: undefined, error: null, isPending: true, fetchStatus: "fetching" }}>{row}</QueryState>,
    )
    expect(screen.getByText("…")).toBeTruthy()
  })

  it("says so when a query is switched off, rather than waiting for ever", () => {
    render(
      <QueryState query={{ data: undefined, error: null, isPending: true, fetchStatus: "idle" }}>{row}</QueryState>,
    )
    expect(screen.queryByRole("status")).toBeNull()
    expect(screen.getByText(/nothing was requested/i)).toBeTruthy()
  })

  it("still draws the waiting shape when nothing said which it is", () => {
    // `fetchStatus` is optional, since a page may hand in a plain object.
    render(<QueryState query={{ data: undefined, error: null, isPending: true }}>{row}</QueryState>)
    expect(screen.getByText("…")).toBeTruthy()
  })

  it("hands the data to the child once it is there", () => {
    render(<QueryState query={{ data: "smp", error: null, isPending: false, fetchStatus: "idle" }}>{row}</QueryState>)
    expect(screen.getByText("smp")).toBeTruthy()
  })

  /** The child is the skeleton, so it is called while the answer is still out. */
  it("calls the child while waiting, so the child can draw its own shape", () => {
    render(
      <QueryState query={{ data: undefined, error: null, isPending: true, fetchStatus: "fetching" }}>{row}</QueryState>,
    )
    expect(screen.getByText("…")).toBeTruthy()
  })

  it("draws flat bars instead when a view asked for them with `rows`", () => {
    render(
      <QueryState query={{ data: undefined, error: null, isPending: true, fetchStatus: "fetching" }} rows={3}>
        {(data: string) => <span>{data}</span>}
      </QueryState>,
    )
    /** Only `Loading` carries `role="status"`, being a box of its own; the shaped branch has no wrapper. */
    expect(screen.getByRole("status")).toBeTruthy()
    expect(screen.queryByText("…")).toBeNull()
  })

  it("shows a failure in place rather than a skeleton that keeps shimmering", () => {
    render(<QueryState query={{ data: undefined, error: new Error("nope"), isPending: false }}>{row}</QueryState>)
    expect(screen.getByRole("alert")).toBeTruthy()
    expect(screen.queryByRole("status")).toBeNull()
  })

  /** The old data stands through a refetch, since this reads `isPending` and not `isFetching`. */
  it("leaves the old data standing while the next answer is fetched", () => {
    render(
      <QueryState query={{ data: "smp", error: null, isPending: false, fetchStatus: "fetching" }}>{row}</QueryState>,
    )
    expect(screen.getByText("smp")).toBeTruthy()
    expect(screen.queryByRole("status")).toBeNull()
  })
})

const gateway = (status: number) => new ApiError(status, "Bad Gateway", "steward-ui")

describe("a gateway that blinked", () => {
  const now = 1_000_000

  it("keeps recent data standing through a 502, 503 or 504", () => {
    for (const status of [502, 503, 504]) {
      expect(transient({ data: "x", error: gateway(status), isPending: false, dataUpdatedAt: now - 5_000 }, now)).toBe(
        true,
      )
    }
  })

  it("does not for another status, data that is too old, or no data at all", () => {
    expect(transient({ data: "x", error: gateway(500), isPending: false, dataUpdatedAt: now }, now)).toBe(false)
    expect(
      transient({ data: "x", error: gateway(502), isPending: false, dataUpdatedAt: now - TRANSIENT_GRACE_MS }, now),
    ).toBe(false)
    expect(transient({ data: undefined, error: gateway(502), isPending: false, dataUpdatedAt: now }, now)).toBe(false)
  })

  it("draws the data instead of the failure", () => {
    render(
      <QueryState query={{ data: "smp", error: gateway(502), isPending: false, dataUpdatedAt: Date.now() }}>
        {row}
      </QueryState>,
    )
    expect(screen.getByText("smp")).toBeTruthy()
    expect(screen.queryByRole("alert")).toBeNull()
  })
})
