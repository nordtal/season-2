import type { Service } from "@/lib/api"

/** The one order every list of services follows. A service missing here follows them in the order it was served. */
const SERVICE_ORDER: readonly string[] = [
  "caddy",
  "proxy",
  "limbo",
  "smp",
  "hunger-games",
  "discord-bot",
  "steward",
  "steward-agent",
  "steward-bunq",
  "postgres",
]

const STANDBY = "-standby"

/** Returns the service a standby stands in for, or `undefined` for a service that is none. */
export function standbyOf(service: string): string | undefined {
  return service.endsWith(STANDBY) && service.length > STANDBY.length ? service.slice(0, -STANDBY.length) : undefined
}

/** Returns where a service stands in {@link SERVICE_ORDER}, past its end for one it does not name. */
export function servicePlace(service: string): number {
  const at = SERVICE_ORDER.indexOf(service)
  return at === -1 ? SERVICE_ORDER.length : at
}

/** Returns the names with every running standby directly below the service it stands in for. */
export function withRunningStandbys(
  names: readonly string[],
  rows: readonly Pick<Service, "service" | "state" | "standby">[],
): string[] {
  const running = rows.filter((row) => row.standby === true && row.state === "running" && !names.includes(row.service))
  return names.flatMap((name) => [
    name,
    ...running.filter((row) => standbyOf(row.service) === name).map((row) => row.service),
  ])
}
