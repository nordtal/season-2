import { HardDrivesIcon, QuestionIcon } from "@phosphor-icons/react"
import { Link } from "@tanstack/react-router"

import { SERVICES } from "@/app/navigation"
import { PersonIdentity } from "@/components/steward/identity"
import { Skeleton, SkeletonText } from "@/components/steward/query-state"
import type { Person } from "@/lib/api"
import { useAvatarBaseUrl, usePeople } from "@/lib/queries"

/**
 * Every identifier Steward shows goes through here: the page hands over what it has, and this
 * decides what that is and how it is drawn.
 *
 * Till, 2026-09-20: one component that recognises an entity and draws it properly, app-wide,
 * instead of each page switching its call sites by hand. A habit holds until the next new page;
 * a component that resolves the identifier itself does not depend on anybody remembering.
 *
 * <ul>
 *   <li><b>Discord snowflake</b> - the person's Discord face, with the identity popover.</li>
 *   <li><b>Minecraft UUID</b> - the person's head and Minecraft name, same popover.</li>
 *   <li><b>Service name</b> - its icon and name, linking to the service page.</li>
 *   <li><b>Anything else</b> - a question mark. An identifier nobody can resolve is shown as such,
 *       not passed through as if it were a name.</li>
 * </ul>
 *
 * A snowflake and an undashed UUID can both be read as digits, so a caller that knows better may
 * pass `kind`. Detection stays the default: a component every call site has to explain to is one
 * the next call site gets wrong.
 */

export type EntityKind = "discord" | "minecraft" | "service"

const SNOWFLAKE = /^\d{17,20}$/
const UUID = /^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$/i

/** What an identifier is, from its shape alone. `unknown` is an answer, not a failure. */
export function entityKind(id: string): EntityKind | "unknown" {
  const trimmed = id.trim()
  if (SNOWFLAKE.test(trimmed)) return "discord"
  if (UUID.test(trimmed)) return "minecraft"
  if ((SERVICES as readonly string[]).includes(trimmed)) return "service"
  return "unknown"
}

/** A UUID in one spelling, so a dashed and an undashed one compare equal. */
function plainUuid(uuid: string): string {
  return uuid.replace(/-/g, "").toLowerCase()
}

export function findPerson(
  people: Person[] | undefined,
  kind: EntityKind,
  id: string,
): Person | undefined {
  if (kind === "discord") return people?.find((person) => person.discordId === id)
  if (kind === "minecraft") {
    const wanted = plainUuid(id)
    return people?.find(
      (person) => person.minecraftUuid !== undefined && plainUuid(person.minecraftUuid) === wanted,
    )
  }
  return undefined
}

export type EntityProps = {
  /** The identifier as the row carries it. Ignored when `system` is set. */
  id?: string
  /** Overrides detection, for an identifier whose shape does not say what it is. */
  kind?: EntityKind
  /** Steward itself acted - the nightly clock, an unattended sweep. Drawn as Steward's mark. */
  system?: boolean
  /** `false` draws the closed face with no popover or link, for places that are interactive. */
  interactive?: boolean
  className?: string
}

export function Entity({ id, kind, system, interactive, className }: EntityProps) {
  const resolved: EntityKind | "unknown" = system
    ? "unknown"
    : (kind ?? entityKind(id ?? ""))
  const isPerson = !system && (resolved === "discord" || resolved === "minecraft")
  const people = usePeople(isPerson)
  const avatarBase = useAvatarBaseUrl(isPerson)

  if (system) return <PersonIdentity system className={className} />

  const value = (id ?? "").trim()

  if (resolved === "discord" || resolved === "minecraft") {
    // Waiting on the roster is not "nobody on record": drawing that for the half second before the
    // roster arrives would state a fact the page does not have yet.
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
    <span
      className={"inline-flex min-w-0 max-w-full items-center gap-1.5 " + (className ?? "")}
      data-entity="unknown"
    >
      <span className="inline-flex size-5 shrink-0 items-center justify-center rounded-full border border-border bg-secondary text-muted-foreground">
        <QuestionIcon aria-hidden className="size-3" />
      </span>
      <span className="truncate text-sm text-muted-foreground">{value || "unknown"}</span>
    </span>
  )
}

/**
 * Who asked for a run or authored an action, from the three fields the backend reads
 * `requested_by` apart into (`StewardUi.ActorFields`, `ActionEntry`). A label with no id behind it
 * ("token-rotation-check", a hand-written requester) resolves to nothing, and says so.
 */
export function Actor({
  system,
  discordId,
  label,
  className,
}: {
  system: boolean
  discordId: string
  label: string
  className?: string
}) {
  if (system) return <Entity system className={className} />
  if (discordId) return <Entity id={discordId} kind="discord" className={className} />
  return <Entity id={label} className={className} />
}
