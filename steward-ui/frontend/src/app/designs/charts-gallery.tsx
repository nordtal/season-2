import { useState } from "react"
import type { ReactNode } from "react"

import { bytes, percent, since } from "@/lib/format"
import { useService } from "@/lib/queries"
import { ServiceHead } from "@/pages/service"
import { DriftBadge, ServiceState } from "@/components/steward/status"
import { Skeleton, SkeletonText } from "@/components/steward/query-state"
import {
  Band,
  RangeSwitch,
  Scrub,
  TallLine,
  Together,
  formatCpu,
  formatRam,
  useRangeMetrics,
} from "@/app/designs/chart-variants"
import type { Range } from "@/app/designs/chart-variants"

/**
 * The comparison page for the CPU and RAM curves in a service page's head.
 *
 * Today's head first, then every proposal in the same row with the same state column beside it, all
 * reading smp's real series - so a proposal is judged in the place it would stand, at the width the
 * device gives it. Deleted with the rest of `app/designs/` once one is picked.
 */

const NAME = "smp"

export function ChartsGalleryPage() {
  const service = useService(NAME).data
  const six = useRangeMetrics(NAME, 6)
  const [hours, setHours] = useState<Range>(6)
  const ranged = useRangeMetrics(NAME, hours)
  const cpu = service ? percent(service.cpuPercent) : undefined
  const ram = service ? bytes(service.memoryBytes) : undefined

  return (
    <div className="flex flex-col gap-8">
      <h1 className="text-2xl font-semibold tracking-tight">Charts</h1>

      <Proposal mark="Now" name="Today">
        <ServiceHead service={service} name={NAME} />
      </Proposal>

      <Proposal mark="A" name="Taller line, peak marked">
        <Head service={service}>
          <TallLine label="CPU" value={cpu} points={six.cpu} format={formatCpu} />
          <TallLine label="RAM" value={ram} points={six.memory} format={formatRam} />
        </Head>
      </Proposal>

      <Proposal mark="B" name="Range band, mean line">
        <Head service={service}>
          <Band label="CPU" value={cpu} points={six.cpu} format={formatCpu} />
          <Band label="RAM" value={ram} points={six.memory} format={formatRam} />
        </Head>
      </Proposal>

      <Proposal mark="C" name="One chart, time axis">
        <Head service={service}>
          <Together cpu={six.cpu} memory={six.memory} cpuValue={cpu} memoryValue={ram} />
        </Head>
      </Proposal>

      <Proposal mark="D" name="Range switch, reading on touch" aside={<RangeSwitch hours={hours} onChange={setHours} />}>
        <Head service={service}>
          <Scrub label="CPU" value={cpu} points={ranged.cpu} format={formatCpu} colour="var(--chart-1)" />
          <Scrub label="RAM" value={ram} points={ranged.memory} format={formatRam} colour="var(--chart-2)" />
        </Head>
      </Proposal>
    </div>
  )
}

function Proposal({ mark, name, aside, children }: { mark: string; name: string; aside?: ReactNode; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-4 border-t border-border pt-4">
      <div className="flex min-h-control flex-wrap items-center justify-between gap-3">
        <h2 className="flex items-baseline gap-2 text-sm">
          <span className="font-semibold">{mark}</span>
          <span className="text-muted-foreground">{name}</span>
        </h2>
        {aside}
      </div>
      {children}
    </section>
  )
}

/** The head's state column as the live head draws it, with the proposal's curves beside it. */
function Head({
  service,
  children,
}: {
  service?: NonNullable<ReturnType<typeof useService>["data"]>
  children: ReactNode
}) {
  return (
    <div className="grid grid-cols-2 gap-x-4 gap-y-5 lg:flex lg:items-start lg:gap-x-10">
      <div className="col-span-2 flex flex-col gap-2 lg:shrink-0">
        <span className="text-xs font-medium font-heading text-muted-foreground">State</span>
        <div className="flex flex-wrap items-center gap-2">
          {service ? (
            <ServiceState state={service.state} health={service.health} hold={service.hold} />
          ) : (
            <Skeleton className="h-5 w-20 rounded-full" />
          )}
          {service ? <DriftBadge drift={service.drift} image={service.image} /> : null}
        </div>
        <span className="text-2xl font-semibold tabular-nums">
          {service ? (
            service.startedAt ? since(service.startedAt) : "–"
          ) : (
            <SkeletonText width="short" className="h-[1lh]" />
          )}
        </span>
      </div>
      {children}
    </div>
  )
}
