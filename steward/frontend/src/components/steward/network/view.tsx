import { useIsMobile } from "@/hooks/use-mobile"

import { useNetwork } from "./data"
import { Field } from "./place"
import { PLAN } from "./plan"
import { NetworkTable } from "./table"
import { QueryState } from "@/components/steward/query-state"

/**
 * The start page's network picture, drawn complete from `PLAN` while only each box's fetched values wait.
 *
 * Below 640px, per `useIsMobile` rather than a measured width, it is {@link NetworkTable}.
 */
export function NetworkPanel() {
  const network = useNetwork()
  const narrow = useIsMobile()

  return (
    <section className="flex min-w-0 flex-col gap-3">
      <h2 className="text-lg font-semibold text-foreground">Network</h2>
      <QueryState
        query={network.query}
        isEmpty={(answer) => answer.services.length === 0}
        empty={{
          title: "No container in the project",
          note: "steward answered, but no container carries the compose project label.",
        }}
      >
        {/* `Field` reads `useNetwork` itself, so it draws with or without an answer. */}
        {() => (narrow ? <NetworkTable /> : <Field plan={PLAN} id="network" />)}
      </QueryState>
    </section>
  )
}
