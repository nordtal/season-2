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

const nothing = (data: string) => <span>{data}</span>

describe("QueryState", () => {
  it("draws skeletons while a query is actually running", () => {
    render(
      <QueryState query={{ data: undefined, error: null, isPending: true, fetchStatus: "fetching" }}>
        {nothing}
      </QueryState>,
    )
    expect(screen.getByRole("status")).toBeTruthy()
  })

  it("says so when a query is switched off, rather than waiting for ever", () => {
    render(
      <QueryState query={{ data: undefined, error: null, isPending: true, fetchStatus: "idle" }}>
        {nothing}
      </QueryState>,
    )
    expect(screen.queryByRole("status")).toBeNull()
    expect(screen.getByText(/nothing was requested/i)).toBeTruthy()
  })

  it("still draws skeletons when nothing said which it is", () => {
    // `fetchStatus` is optional, because a page may hand in a plain object rather than a query.
    render(
      <QueryState query={{ data: undefined, error: null, isPending: true }}>{nothing}</QueryState>,
    )
    expect(screen.getByRole("status")).toBeTruthy()
  })

  it("hands the data to the child once it is there", () => {
    render(
      <QueryState query={{ data: "smp", error: null, isPending: false, fetchStatus: "idle" }}>
        {nothing}
      </QueryState>,
    )
    expect(screen.getByText("smp")).toBeTruthy()
  })
})
