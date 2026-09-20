import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import { QueryState } from "@/components/steward/query-state"

/**
 * A query that was never started must not look like one that is still running.
 *
 * **Measured on 2026-09-14** on `/configuration`: the page showed skeletons and a button and
 * nothing else, for as long as anybody was willing to wait. The mechanism is that TanStack Query
 * reports a *disabled* query as `isPending` forever - there is no data and there never will be -
 * and this component drew skeletons for `isPending`. So the interface said "loading" about
 * something nobody had asked for, which is the one thing a loading state must not be able to say.
 *
 * `fetchStatus` is what separates them: `"fetching"` is on its way, `"paused"` is waiting for a
 * network, and `"idle"` next to `isPending` means the query is switched off. That page is gone
 * (the configuration lives on each service's page now), but the trap is not - every `enabled:`
 * in `queries.ts` can lead back here.
 */

afterEach(cleanup)

/**
 * The shape this component asks a child for since steward/120: one expression, called with the
 * data and without it. A row that has no name yet still draws the row.
 */
const row = (data: string | undefined) => <span>{data ?? "…"}</span>

describe("QueryState", () => {
  it("draws skeletons while a query is actually running", () => {
    render(
      <QueryState query={{ data: undefined, error: null, isPending: true, fetchStatus: "fetching" }}>
        {row}
      </QueryState>,
    )
    expect(screen.getByRole("status")).toBeTruthy()
  })

  it("says so when a query is switched off, rather than waiting for ever", () => {
    render(
      <QueryState query={{ data: undefined, error: null, isPending: true, fetchStatus: "idle" }}>
        {row}
      </QueryState>,
    )
    expect(screen.queryByRole("status")).toBeNull()
    expect(screen.getByText(/nothing was requested/i)).toBeTruthy()
  })

  it("still draws skeletons when nothing said which it is", () => {
    // `fetchStatus` is optional, because a page may hand in a plain object rather than a query.
    render(
      <QueryState query={{ data: undefined, error: null, isPending: true }}>{row}</QueryState>,
    )
    expect(screen.getByRole("status")).toBeTruthy()
  })

  it("hands the data to the child once it is there", () => {
    render(
      <QueryState query={{ data: "smp", error: null, isPending: false, fetchStatus: "idle" }}>
        {row}
      </QueryState>,
    )
    expect(screen.getByText("smp")).toBeTruthy()
  })

  /**
   * steward/120: the child is the skeleton, so it has to be called while the answer is still out.
   *
   * This is the assertion the whole ticket rests on. A `QueryState` that only rendered its child
   * once the data was there would leave every converted component's waiting shape unreachable -
   * and the page would go back to flat grey bars without a single test turning red.
   */
  it("calls the child while waiting, so the child can draw its own shape", () => {
    render(
      <QueryState query={{ data: undefined, error: null, isPending: true, fetchStatus: "fetching" }}>
        {row}
      </QueryState>,
    )
    expect(screen.getByText("…")).toBeTruthy()
  })

  it("draws flat bars instead when a view asked for them with `rows`", () => {
    render(
      <QueryState
        query={{ data: undefined, error: null, isPending: true, fetchStatus: "fetching" }}
        rows={3}
      >
        {(data: string) => <span>{data}</span>}
      </QueryState>,
    )
    expect(screen.getByRole("status")).toBeTruthy()
    expect(screen.queryByText("…")).toBeNull()
  })

  it("shows a failure in place rather than a skeleton that keeps shimmering", () => {
    render(
      <QueryState query={{ data: undefined, error: new Error("nope"), isPending: false }}>
        {row}
      </QueryState>,
    )
    expect(screen.getByRole("alert")).toBeTruthy()
    expect(screen.queryByRole("status")).toBeNull()
  })

  /**
   * Nothing jumps on a refetch. TanStack Query keeps the old data and drops `isPending`, so this
   * is really an assertion about which of the two this component reads - `isFetching` here would
   * grey the page out on every poll.
   */
  it("leaves the old data standing while the next answer is fetched", () => {
    render(
      <QueryState
        query={{ data: "smp", error: null, isPending: false, fetchStatus: "fetching" }}
      >
        {row}
      </QueryState>,
    )
    expect(screen.getByText("smp")).toBeTruthy()
    expect(screen.queryByRole("status")).toBeNull()
  })
})
