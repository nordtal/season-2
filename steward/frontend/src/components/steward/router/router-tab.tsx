import { useServices } from "@/lib/queries"
import { useSeasonRouter } from "@/lib/queries-season"
import { useNow } from "@/lib/use-now"
import { QueryState } from "@/components/steward/query-state"
import { GateBar } from "@/components/steward/router/gate-bar"
import { LaunchCard } from "@/components/steward/router/launch-card"
import { MaintenanceCard } from "@/components/steward/router/maintenance-card"
import { ModeCard } from "@/components/steward/router/mode-card"
import { RunCard } from "@/components/steward/router/run-card"
import { LIMBO } from "@/components/steward/router/router-state"

/** The proxy's Router tab: where members land now, and the launch date, maintenance and the game mode that decide it. */
export function RouterTab() {
  const season = useSeasonRouter()
  const table = useServices()
  const now = useNow(season.dataUpdatedAt)
  const services = table.data?.services ?? []
  const waiting = services.find((row) => row.service === LIMBO)?.players ?? 0

  return (
    <QueryState query={season} rows={4}>
      {(router) => (
        <div className="flex flex-col gap-6">
          <GateBar router={router} services={services} now={now} />
          <div className="grid grid-cols-1 items-start gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)] lg:items-stretch xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_minmax(0,1.35fr)]">
            <MaintenanceCard router={router} waiting={waiting} now={now} />
            <LaunchCard router={router} now={now} />
            <ModeCard router={router} services={services} className="lg:col-span-full xl:col-auto" />
            <RunCard router={router} className="lg:col-span-full xl:col-span-1 xl:col-start-3" />
          </div>
        </div>
      )}
    </QueryState>
  )
}
