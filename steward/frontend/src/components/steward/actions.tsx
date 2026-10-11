import { ShieldWarningIcon } from "@phosphor-icons/react"
import { TERM_ICON } from "@/components/steward/term-icons"
import type { Icon } from "@phosphor-icons/react"
import type { Action } from "@/lib/api"
import { dateTime, relative } from "@/lib/format"
import { message, t } from "@/lib/texts"
import { Actor } from "@/components/steward/entity"
import { Skeleton, SkeletonText } from "@/components/ui/skeleton"

/** One icon per {@link Action.kind}, each its term's. */
const KIND_ICON: Record<string, Icon> = {
  UPDATE: TERM_ICON.update,
  BACKUP: TERM_ICON.backup,
  RESTART: TERM_ICON.restart,
  DOWN: TERM_ICON.down,
  START: TERM_ICON.start,
  GRANT_ACCESS: TERM_ICON.grantAccess,
  REVOKE_ACCESS: TERM_ICON.revokeAccess,
  LINK: TERM_ICON.link,
  UNLINK: TERM_ICON.unlink,
  SETTLE: TERM_ICON.settle,
  RECREATE: TERM_ICON.recreate,
  SET_PHASE: TERM_ICON.phase,
  REGISTER_KEY: TERM_ICON.securityKey,
  REMOVE_KEY: TERM_ICON.securityKey,
  FORGET_FACTORS: ShieldWarningIcon,
  CANCEL_RUN: TERM_ICON.cancel,
}

function iconOf(kind: string): Icon {
  return KIND_ICON[kind] ?? TERM_ICON.journal
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
  const icons = { Icon: action ? iconOf(action.kind) : TERM_ICON.journal }

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
              <span>{t("steward.overview.authored")}</span>
              <span title={dateTime(action.occurred)}>{relative(action.occurred, now)}</span>
            </>
          ) : null}
        </div>
      </div>
    </li>
  )
}
