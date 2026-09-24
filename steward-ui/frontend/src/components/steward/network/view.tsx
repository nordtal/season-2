import { useIsMobile } from "@/hooks/use-mobile"

import { useNetwork } from "./data"
import { Field } from "./place"
import { PLAN } from "./plan"
import { NetworkTable } from "./table"
import { QueryState } from "@/components/steward/query-state"

/**
 * The network picture, in the product (steward/81) - the left half of the start page's bottom
 * section, and the thing that replaced steward/64's "10 of 10 healthy" disclosure there.
 *
 * <h2>A heading, and the same one the other half has</h2>
 * The right half of the section (`ActionsPanel`, steward/82) carries a real heading rather than
 * `Panel`'s small grey capitals, because steward/77 decided that content gets the weight a title
 * gets. Two halves of one section with only one of them titled reads as a picture that fell out of
 * the page, so this one is titled the same way and by the same rule - and with the one word that
 * says what it is, because nothing else here explains itself in prose.
 *
 * <h2>What it does when the query has nothing</h2>
 * **The picture itself is the waiting shape** (steward/120), and it is the one place in this
 * interface where that costs nothing at all: the boxes, their names and every line between them
 * come from `PLAN` and from `topology.ts`, not from the worker. So the diagram is drawn complete
 * and only the three fetched things inside a box wait - the health dot, the image tag and the
 * player count. Nothing moves when `/api/services` answers.
 *
 * The other two states are unchanged: the retryable failure when it did not answer, and the "no
 * container in the project" empty state when it answered with none. That last one is worth keeping
 * even though it is nearly impossible on a running host: a picture of ten boxes drawn from an empty
 * list is ten boxes saying nothing, which looks like a stack that is fine.
 *
 * <h2>Below 640px it is a table, and the line is the app's own (steward/121)</h2>
 * `useIsMobile` - the same 640px that decides whether a dialog is a dialog or a bottom sheet, and
 * whether the command palette prints its right-hand column - is what picks between the drawing and
 * {@link NetworkTable}. It is deliberately **not** a measured container width, which is what the
 * old `wide`/`narrow` switch used: that measurement is 0 in jsdom, so the branch it controlled was
 * invisible to every test, and "the table is for a phone" is a decision about the device rather
 * than about how many pixels this particular panel happens to have been given.
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
          note: "steward-worker answered, but no container carries the compose project label.",
        }}
      >
        {/* `Field` reads `useNetwork` itself, so it draws with or without an answer - which is
            exactly the shape this component is asking for. */}
        {() => (narrow ? <NetworkTable /> : <Field plan={PLAN} id="network" />)}
      </QueryState>
    </section>
  )
}
