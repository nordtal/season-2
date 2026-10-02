import { HardDrivesIcon, QuestionIcon } from "@phosphor-icons/react"
import { Link } from "@tanstack/react-router"

import { PersonIdentity } from "@/components/steward/identity"
import { Skeleton, SkeletonText } from "@/components/steward/query-state"
import type { ActorKind, Person } from "@/lib/api"
import { useAvatarBaseUrl, usePeople, useTopology } from "@/lib/queries"

export type EntityKind = "discord" | "minecraft" | "service"

const SNOWFLAKE = /^\d{17,20}$/
const UUID = /^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$/i

/** What an identifier is, from its shape or from the served `services`; `unknown` is an answer, not a failure. */
export function entityKind(id: string, services: readonly string[]): EntityKind | "unknown" {
  const trimmed = id.trim()
  if (SNOWFLAKE.test(trimmed)) return "discord"
  if (UUID.test(trimmed)) return "minecraft"
  if (services.includes(trimmed)) return "service"
  return "unknown"
}

function plainUuid(uuid: string): string {
  return uuid.replace(/-/g, "").toLowerCase()
}

export function findPerson(people: Person[] | undefined, kind: EntityKind, id: string): Person | undefined {
  if (kind === "discord") return people?.find((person) => person.discordId === id)
  if (kind === "minecraft") {
    const wanted = plainUuid(id)
    return people?.find((person) => person.minecraftUuid !== undefined && plainUuid(person.minecraftUuid) === wanted)
  }
  return undefined
}

export type EntityProps = {
  /** The identifier as the row carries it. Ignored when `system` is set. */
  id?: string
  /** Overrides detection, for an identifier whose shape does not say what it is. */
  kind?: EntityKind
  /** Steward itself acted, such as the nightly clock, drawn as Steward's mark. */
  system?: boolean
  /** `false` draws the closed face with no popover or link, for places that are interactive. */
  interactive?: boolean
  className?: string
}

/**
 * Draws any identifier Steward shows, deciding from its shape whether it is a person, a service or unknown.
 *
 * An unknown one shows a question mark rather than passing for a name; `kind` overrides the detection.
 */
export function Entity({ id, kind, system, interactive, className }: EntityProps) {
  /** Asked only for a name that is neither a Discord id nor a UUID, the one case the shape cannot settle. */
  const byName = !system && kind === undefined && entityKind(id ?? "", []) === "unknown"
  const topology = useTopology(byName)
  const services = topology.data?.services.map((box) => box.name) ?? []
  const resolved: EntityKind | "unknown" = system ? "unknown" : (kind ?? entityKind(id ?? "", services))
  const isPerson = !system && (resolved === "discord" || resolved === "minecraft")
  const people = usePeople(isPerson)
  const avatarBase = useAvatarBaseUrl(isPerson)

  if (system) return <PersonIdentity system className={className} />

  const value = (id ?? "").trim()

  /** Waiting on the map is not "no such service", which the question mark would claim. */
  if (byName && topology.isPending) {
    return <SkeletonText width="medium" className={"text-sm " + (className ?? "")} />
  }

  if (resolved === "discord" || resolved === "minecraft") {
    /** Waiting on the roster is not "nobody on record", which would state a fact the page does not have. */
    if (people.isPending || avatarBase.isPending) {
      return (
        <span className={"inline-flex min-w-0 items-center gap-2 " + (className ?? "")}>
          <Skeleton className="size-5 shrink-0 rounded-full" />
          <SkeletonText width="medium" className="text-sm" />
        </span>
      )
    }
    const known = findPerson(people.data, resolved, value)
    return (
      <PersonIdentity
        face={resolved === "minecraft" ? "minecraft" : "discord"}
        interactive={interactive}
        discordId={resolved === "discord" ? value : known?.discordId}
        discordUsername={known?.discordUsername}
        discordDisplayName={known?.discordDisplayName}
        discordAvatarUrl={known?.discordAvatarUrl}
        mcUuid={resolved === "minecraft" ? value : known?.minecraftUuid}
        mcName={known?.mcName}
        avatarBaseUrl={avatarBase.data}
        className={className}
      />
    )
  }

  if (resolved === "service") {
    const face = (
      <>
        <HardDrivesIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
        <span className="truncate text-sm text-foreground">{value}</span>
      </>
    )
    const box = "inline-flex min-w-0 max-w-full items-center gap-1.5 " + (className ?? "")
    if (interactive === false) return <span className={box}>{face}</span>
    return (
      <Link
        to="/services/$name"
        params={{ name: value }}
        className={box + " underline-offset-4 hover:text-primary hover:underline"}
      >
        {face}
      </Link>
    )
  }

  return (
    <span className={"inline-flex min-w-0 max-w-full items-center gap-1.5 " + (className ?? "")} data-entity="unknown">
      <span className="inline-flex size-5 shrink-0 items-center justify-center rounded-full border border-border bg-secondary text-muted-foreground">
        <QuestionIcon aria-hidden className="size-3" />
      </span>
      <span className="truncate text-sm text-muted-foreground">{value || "unknown"}</span>
    </span>
  )
}

/** Who asked for a run or authored an action: a person by their Discord id, Steward itself, or the host. */
export function Actor({ kind, id, className }: { kind: ActorKind; id: string; className?: string }) {
  if (kind === "PERSON" && id) return <Entity id={id} kind="discord" className={className} />
  if (kind === "HOST") return <Entity id="host" className={className} />
  return <Entity system className={className} />
}
