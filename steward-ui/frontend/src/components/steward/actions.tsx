
import {
  ArchiveIcon,
  ArrowClockwiseIcon,
  ArrowCounterClockwiseIcon,
  ArrowsClockwiseIcon,
  ClipboardTextIcon,
  FlagIcon,
  HandCoinsIcon,
  KeyIcon,
  LinkBreakIcon,
  LinkSimpleIcon,
  PulseIcon,
  ShieldCheckIcon,
  ShieldSlashIcon,
  ShieldWarningIcon,
} from "@phosphor-icons/react"
import type { Icon } from "@phosphor-icons/react"
import type { Action, Person } from "@/lib/api"
import { dateTime, relative } from "@/lib/format"
import { RUN_KIND } from "@/components/steward/status"
import { PersonIdentity } from "@/components/steward/identity"

/**
 * The five-item feed steward/82 asked for (`ActionsApi` on steward-worker's side, `useActions()`
 * here). One row, drawn once so the icon, the outcome and the person are the same shape everywhere
 * this list is used - today that is only {@code OverviewPage}'s "Latest actions" panel, but a second
 * place would otherwise be the seam where the icon map and {@link RUN_KIND} quietly drift apart.
 */

/**
 * A journal action this list draws a dedicated icon and label for. Anything not in here still shows
 * - as its own raw {@link Action.kind} string and a generic icon - because {@code audit_log.action}
 * has no schema (see {@code AuditEntry}'s own javadoc): a value this map has not been told about yet
 * is a fact about the database, not a reason to draw nothing.
 */
const AUDIT_LABEL: Record<string, string> = {
  GRANT_ACCESS: "Access granted",
  REVOKE_ACCESS: "Access revoked",
  LINK: "Account linked",
  UNLINK: "Account unlinked",
  SETTLE: "Payment settled",
  RECREATE: "Service recreated",
  SET_PHASE: "Phase changed",
  REGISTER_KEY: "Security key added",
  REMOVE_KEY: "Security key removed",
  FORGET_FACTORS: "Security reset",
}

/**
 * One icon per {@link Action.kind}. The three run kinds reuse the exact icons `operations.tsx`
 * already draws them with ({@link ArrowsClockwiseIcon}, {@link ArchiveIcon}, {@link ArrowCounterClockwiseIcon}) - a second choice
 * for the same fact would be the interface disagreeing with itself between two pages.
 */
const KIND_ICON: Record<string, Icon> = {
  UPDATE: ArrowsClockwiseIcon,
  BACKUP: ArchiveIcon,
  RESTART: ArrowCounterClockwiseIcon,
  REPORT: ClipboardTextIcon,
  GRANT_ACCESS: ShieldCheckIcon,
  REVOKE_ACCESS: ShieldSlashIcon,
  LINK: LinkSimpleIcon,
  UNLINK: LinkBreakIcon,
  SETTLE: HandCoinsIcon,
  RECREATE: ArrowClockwiseIcon,
  SET_PHASE: FlagIcon,
  REGISTER_KEY: KeyIcon,
  REMOVE_KEY: KeyIcon,
  FORGET_FACTORS: ShieldWarningIcon,
}

function labelOf(kind: string): string {
  return RUN_KIND[kind] ?? AUDIT_LABEL[kind] ?? kind
}

function iconOf(kind: string): Icon {
  return KIND_ICON[kind] ?? PulseIcon
}

/** One row: an icon for the kind, the label and its extent, then who is credited and when. */
export function ActionRow({
  action,
  people,
  avatarBaseUrl,
  now,
}: {
  action: Action
  people: Person[] | undefined
  avatarBaseUrl: string | undefined
  now: number
}) {
  const Icon = iconOf(action.kind)
  const known = action.actorDiscordId
    ? people?.find((person) => person.discordId === action.actorDiscordId)
    : undefined

  return (
    <li className="flex items-start gap-3 border-b border-border/60 py-3 last:border-0">
      <span
        className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-full border border-border bg-secondary text-muted-foreground"
        aria-hidden
      >
        <Icon className="size-4" />
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="truncate text-sm font-medium">{labelOf(action.kind)}</span>
        <span className="truncate text-xs text-muted-foreground">{action.extent}</span>
        <div className="flex min-w-0 items-center gap-1 text-xs text-muted-foreground">
          {action.system ? (
            <PersonIdentity system now={now} />
          ) : action.actorDiscordId ? (
            <PersonIdentity
              discordId={action.actorDiscordId}
              discordUsername={known?.discordUsername}
              discordUsernameUpdated={known?.discordUsernameUpdated}
              discordDisplayName={known?.discordDisplayName}
              discordDisplayNameUpdated={known?.discordDisplayNameUpdated}
              discordAvatarUrl={known?.discordAvatarUrl}
              discordAvatarUrlUpdated={known?.discordAvatarUrlUpdated}
              mcUuid={known?.minecraftUuid}
              mcName={known?.mcName}
              mcNameUpdated={known?.mcNameUpdated}
              avatarBaseUrl={avatarBaseUrl}
              now={now}
            />
          ) : (
            // A console request with no id behind it at all - "token-rotation-check", a nightly
            // clock is already `system`. Plain text, deliberately: there is nothing here that
            // safely resolves this to a person, and PersonIdentity is for people.
            <span className="truncate">{action.actorLabel || "console"}</span>
          )}
          <span>authored</span>
          <span title={dateTime(action.occurred)}>{relative(action.occurred, now)}</span>
        </div>
      </div>
    </li>
  )
}
