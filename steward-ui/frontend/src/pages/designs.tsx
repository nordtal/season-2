/**
 * The design variants, served so that they can be clicked rather than described (steward/81,
 * steward/89).
 *
 * Till asked for several real design proposals built with shadcn and, on 2026-09-17, for them live and
 * clickable rather than written down: he picks a letter after looking at them on his phone. So each
 * variant is real code against the real queries, reachable at a route the sidebar does not list -
 * `navigation.ts` stays as it is, because three throwaway entries in the navigation are three
 * entries somebody has to remember to take out again.
 *
 * **These routes are temporary and their deletion is part of the ticket that owns them.** When a
 * variant is chosen it moves to where it belongs and this file loses the others.
 *
 * <h2>Three rounds, and only the third is still here</h2>
 * `a` to `c` were the first round and `d` to `g` the second; both are deleted. Till chose no letter
 * out of the seven and replied with seven changes instead (steward/81, 2026-09-17): curved lines
 * rather than the orthogonal routing of `d`/`f`/`g`, real space between cards so a connection can
 * be seen at all, an arrangement that is not a grid, every card the same size and smaller -
 * `postgres` included, which was a bar across three of them - a ghost recreate, two more tooltips,
 * and the half-dark tooltip finished. Every one of those is a change to what all seven drafts
 * shared, so keeping them would have meant carrying seven rejected arrangements through a rewrite
 * of the thing they were made of. They are gone, and what replaced them is `h` and `i`.
 *
 * **They are drawn at the width of the page, not of their eventual home.** steward/81 puts the
 * winner in the left half of a split section, so each draft reacts to its own container rather than
 * to the window (`@container`, not `lg:`): whatever width it is given later, it takes the form that
 * fits.
 */
import type { ComponentType } from "react"

import { Link } from "@tanstack/react-router"
import { useRouterState } from "@tanstack/react-router"

import { PageHeader } from "@/components/steward/page-header"
import { Empty, Failure, Loading } from "@/components/steward/query-state"
import { useNetwork } from "@/components/steward/network/data"
import { DeltaDraft } from "@/components/steward/network/h"
import { LensDraft } from "@/components/steward/network/i"
import { Button } from "@/components/ui/button"

const VARIANTS = ["h", "i"] as const

type Variant = (typeof VARIANTS)[number]

/** `?v=h` - anything else is `h`, so a hand-typed address never shows an empty page. */
export function useVariant(): Variant {
  const search = useRouterState({ select: (state) => state.location.searchStr })
  const asked = new URLSearchParams(search).get("v")
  return (VARIANTS as readonly string[]).includes(asked ?? "") ? (asked as Variant) : "h"
}

const DRAFTS: Record<Variant, ComponentType> = {
  h: DeltaDraft,
  i: LensDraft,
}

export function NetworkDesignsPage() {
  const variant = useVariant()
  const network = useNetwork()
  const Draft = DRAFTS[variant]

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Network"
        actions={
          <div className="flex flex-wrap items-center gap-1">
            {VARIANTS.map((letter) => (
              <Button
                key={letter}
                asChild
                size="sm"
                variant={letter === variant ? "secondary" : "ghost"}
              >
                {/* The route takes no typed search, on purpose: it is a scratch route and giving
                    it a schema would be a piece of the router to unpick when it goes. */}
                <Link to="/designs/network" search={{ v: letter } as never}>
                  {letter}
                </Link>
              </Button>
            ))}
          </div>
        }
      />

      {network.query.isPending ? (
        <Loading rows={4} />
      ) : network.query.error ? (
        <Failure error={network.query.error} onRetry={network.query.refetch} />
      ) : (network.query.data?.services.length ?? 0) === 0 ? (
        <Empty
          title="No container in the project"
          note="steward-worker answered, but no container carries the compose project label."
        />
      ) : (
        <Draft />
      )}
    </div>
  )
}
