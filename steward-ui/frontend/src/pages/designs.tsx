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
 * **What the three differ in is shape, and only shape.** Same boxes, same contents, same edges,
 * same colours - see `components/steward/network/node.tsx`, which all three render. A choice
 * between three pictures that differed in two things at once would not be a choice about either.
 *
 * **They are drawn at the width of the page, not of their eventual home.** steward/81 puts the
 * winner in the left half of a split section, so each draft reacts to its own container rather than
 * to the window (`@container`, not `lg:`): whatever width it is given later, it takes the form that
 * fits. What that means for reading the drafts is written on each of them, and it is the one thing
 * to know before choosing - MEASURED on 2026-09-17, half of a 1440px page is **580px**, and at
 * 580px `a` draws its stacked tree rather than its columns (it needs 768) and `c` draws its fan
 * rather than its circle (it needs 660). Only `b` is the same picture at every width.
 */
import { Link } from "@tanstack/react-router"
import { useRouterState } from "@tanstack/react-router"

import { PageHeader } from "@/components/steward/page-header"
import { Empty, Failure, Loading } from "@/components/steward/query-state"
import { useNetwork } from "@/components/steward/network/data"
import { FlowDraft } from "@/components/steward/network/flow"
import { LayersDraft } from "@/components/steward/network/layers"
import { RingDraft } from "@/components/steward/network/ring"
import { Button } from "@/components/ui/button"

/** `?v=a` - anything else is `a`, so a hand-typed address never shows an empty page. */
export function useVariant(): "a" | "b" | "c" {
  const search = useRouterState({ select: (state) => state.location.searchStr })
  const asked = new URLSearchParams(search).get("v")
  return asked === "b" || asked === "c" ? asked : "a"
}

const VARIANTS = ["a", "b", "c"] as const

export function NetworkDesignsPage() {
  const variant = useVariant()
  const network = useNetwork()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Network"
        actions={
          <div className="flex items-center gap-1">
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
      ) : variant === "b" ? (
        <LayersDraft />
      ) : variant === "c" ? (
        <RingDraft />
      ) : (
        <FlowDraft />
      )}
    </div>
  )
}
