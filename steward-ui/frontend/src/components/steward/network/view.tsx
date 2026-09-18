import { useNetwork } from "./data"
import { Field } from "./place"
import { PLAN } from "./plan"
import { Empty, Failure, Loading } from "@/components/steward/query-state"

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
 * Exactly what the disclosure it replaced did, in the same three components: skeleton rows while
 * `/api/services` is on its way, the retryable failure when it did not answer, and the "no
 * container in the project" empty state when it answered with none. That last one is worth keeping
 * even though it is nearly impossible on a running host: a picture of ten boxes drawn from an empty
 * list is ten boxes saying nothing, which looks like a stack that is fine.
 */
export function NetworkPanel() {
  const network = useNetwork()

  return (
    <section className="flex min-w-0 flex-col gap-3">
      <h2 className="text-lg font-semibold text-foreground">Network</h2>
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
        <Field plan={PLAN} id="network" />
      )}
    </section>
  )
}
