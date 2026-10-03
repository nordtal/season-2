import {
  ArchiveIcon,
  ArrowClockwiseIcon,
  ArrowCounterClockwiseIcon,
  ArrowsClockwiseIcon,
  FlagIcon,
  HandCoinsIcon,
  KeyIcon,
  LinkBreakIcon,
  LinkSimpleIcon,
  PlayIcon,
  PulseIcon,
  ShieldCheckIcon,
  ShieldSlashIcon,
  ShieldWarningIcon,
  StopIcon,
  XCircleIcon,
} from "@phosphor-icons/react"
import type { Icon } from "@phosphor-icons/react"
import type { Action } from "@/lib/api"
import { dateTime, relative } from "@/lib/format"
import { message } from "@/lib/texts"
import { Actor } from "@/components/steward/entity"
import { Skeleton, SkeletonText } from "@/components/ui/skeleton"

/** One icon per {@link Action.kind}; the run kinds reuse the icons `operations.tsx` draws them with. */
const KIND_ICON: Record<string, Icon> = {
  UPDATE: ArrowsClockwiseIcon,
  BACKUP: ArchiveIcon,
  RESTART: ArrowCounterClockwiseIcon,
  DOWN: StopIcon,
  START: PlayIcon,
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
  CANCEL_RUN: XCircleIcon,
}

function iconOf(kind: string): Icon {
  return KIND_ICON[kind] ?? PulseIcon
}

/** One row: an icon for the kind, the label and its extent, then who is credited and when. */
export function ActionRow({
  action,
  now,
}: {
  /** Absent while the feed is still loading: the row is then drawn empty. */
  action?: Action
  now: number
}) {
  /** A property access, so the chosen icon reads as an existing component rather than one defined in place. */
  const icons = { Icon: action ? iconOf(action.kind) : PulseIcon }

  return (
    <li className="flex items-start gap-3 border-b border-border/60 py-3 last:border-0">
      {action ? (
        <span
          className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-full border border-border bg-secondary text-muted-foreground"
          aria-hidden
        >
          <icons.Icon className="size-4" />
        </span>
      ) : (
        <Skeleton className="mt-0.5 size-8 shrink-0 rounded-full" />
      )}
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        {action ? (
          <>
            <span className="truncate text-sm font-medium">{message(action.label)}</span>
            <span className="truncate text-xs text-muted-foreground">{message(action.extent)}</span>
          </>
        ) : (
          <>
            <SkeletonText className="w-40 text-sm" />
            <SkeletonText className="w-56 text-xs" />
          </>
        )}
        <div className="flex min-w-0 items-center gap-1 text-xs text-muted-foreground">
          {!action ? <SkeletonText className="w-32 text-xs" /> : <Actor kind={action.actorKind} id={action.actorId} />}
          {action ? (
            <>
              <span>authored</span>
              <span title={dateTime(action.occurred)}>{relative(action.occurred, now)}</span>
            </>
          ) : null}
        </div>
      </div>
    </li>
  )
}
