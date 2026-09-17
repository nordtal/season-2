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
 * <h2>Two rounds</h2>
 * `a`, `b` and `c` were the first round and drew the same boxes, the same edges and the same
 * colours - see `components/steward/network/node.tsx`, which every one of the seven drafts renders
 * - so that the only thing being chosen between them was shape. The orchestrator's own read of
 * their screenshots found the lines themselves close to unreadable regardless of shape - a
 * `strokeOpacity` of 0.18-0.4 on a near-black background, edges hidden underneath boxes rather than
 * routed around them, and seven database edges fanning into `postgres` from every direction at
 * once. `d`, `e`, `f` and `g` are the second round, built to answer that finding directly: every one
 * of them draws in two named colours rather than one faint one, rounds every corner, and gives each
 * node a small toolbar - `open` and, where the deployer allows it, `recreate` - because Till asked
 * for the whole section to be "sehr interaktiv" and not only a picture. What still differs between
 * `d`-`g` is no longer only shape; it is what a reader is asked to do to read the database edges at
 * all: always look at a rail (`d`), look at one node at a time (`e`), never have to look far because
 * the layout already put the ends close together (`f`), or not look at them until asked (`g`).
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
import { RouteMapDraft } from "@/components/steward/network/d"
import { FocusDraft } from "@/components/steward/network/e"
import { FlowDraft } from "@/components/steward/network/flow"
import { NeighbourhoodDraft } from "@/components/steward/network/f"
import { ChannelDraft } from "@/components/steward/network/g"
import { LayersDraft } from "@/components/steward/network/layers"
import { RingDraft } from "@/components/steward/network/ring"
import { Button } from "@/components/ui/button"

const VARIANTS = ["a", "b", "c", "d", "e", "f", "g"] as const

type Variant = (typeof VARIANTS)[number]

/** `?v=a` - anything else is `a`, so a hand-typed address never shows an empty page. */
export function useVariant(): Variant {
  const search = useRouterState({ select: (state) => state.location.searchStr })
  const asked = new URLSearchParams(search).get("v")
  return (VARIANTS as readonly string[]).includes(asked ?? "") ? (asked as Variant) : "a"
}

const DRAFTS: Record<Variant, ComponentType> = {
  a: FlowDraft,
  b: LayersDraft,
  c: RingDraft,
  d: RouteMapDraft,
  e: FocusDraft,
  f: NeighbourhoodDraft,
  g: ChannelDraft,
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
