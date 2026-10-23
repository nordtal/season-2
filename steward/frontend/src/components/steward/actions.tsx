import { ShieldWarningIcon } from "@phosphor-icons/react"
import { TERM_ICON } from "@/components/steward/term-icons"
import type { Icon } from "@phosphor-icons/react"
import type { Action } from "@/lib/api"
import { dateTime, relative } from "@/lib/format"
import { message, t } from "@/lib/texts"
import { Actor } from "@/components/steward/entity"
import { Skeleton, SkeletonText } from "@/components/ui/skeleton"

/** An action's icon, by inbox kind or journal action, so the overview and the journal's filter draw the same one. */
const KIND_ICON: Record<string, Icon> = {
  UPDATE: TERM_ICON.update,
  BACKUP: TERM_ICON.backup,
  DOWNLOAD_BACKUP: TERM_ICON.backup,
  BACKUP_STALE: TERM_ICON.backup,
  RESTART: TERM_ICON.restart,
  DOWN: TERM_ICON.down,
  SERVICE_DOWN: TERM_ICON.down,
  START: TERM_ICON.start,
  GRANT_ACCESS: TERM_ICON.grantAccess,
  UNANCHORED_GRANT: TERM_ICON.grantAccess,
  REVOKE_ACCESS: TERM_ICON.revokeAccess,
  LINK: TERM_ICON.link,
  LINK_CODES_EXHAUSTED: TERM_ICON.link,
  LINK_REFUSED: TERM_ICON.unlink,
  LINK_FAILED: TERM_ICON.unlink,
  UNLINK: TERM_ICON.unlink,
  SETTLE: TERM_ICON.settle,
  PAYMENT_NEEDS_LOOK: TERM_ICON.payments,
  PURCHASE_FAILED: TERM_ICON.payments,
  RECREATE: TERM_ICON.recreate,
  IMAGE_DRIFT: TERM_ICON.recreate,
  SET_PHASE: TERM_ICON.phase,
  SET_LAUNCH: TERM_ICON.seasonDates,
  SET_SMP_START: TERM_ICON.seasonDates,
  ADMIN_ROOT: TERM_ICON.admin,
  GRANT_ADMIN: TERM_ICON.admin,
  REVOKE_ADMIN: TERM_ICON.revokeAdmin,
  ENFORCE_PACK: TERM_ICON.pack,
  EXEMPT_PACK: TERM_ICON.pack,
  PACK_DEAD: TERM_ICON.pack,
  SET_PLAYTIME: TERM_ICON.playtime,
  SET_AURA: TERM_ICON.aura,
  SET_SPINS: TERM_ICON.spins,
  REGISTER_KEY: TERM_ICON.securityKey,
  HELD_KEY: TERM_ICON.securityKey,
  RENAME_KEY: TERM_ICON.securityKey,
  REMOVE_KEY: TERM_ICON.securityKey,
  ADD_AUTHENTICATOR: TERM_ICON.authenticatorApp,
  USED_AUTHENTICATOR: TERM_ICON.authenticatorApp,
  REMOVE_AUTHENTICATOR: TERM_ICON.authenticatorApp,
  FORGET_FACTORS: ShieldWarningIcon,
  WEB_PUSH_SUBSCRIBE: TERM_ICON.alert,
  WEB_PUSH_UNSUBSCRIBE: TERM_ICON.alert,
  WEB_PUSH_TEST: TERM_ICON.alert,
  SET_ALERT_PREFERENCE: TERM_ICON.alert,
  CONSOLE: TERM_ICON.console,
  SAVE_SETTINGS: TERM_ICON.settings,
  SAVE_MESSAGES: TERM_ICON.translations,
  ADD_PLUGIN: TERM_ICON.plugins,
  REMOVE_PLUGIN: TERM_ICON.plugins,
  REQUEST_RUN: TERM_ICON.update,
  CANCEL_RUN: TERM_ICON.cancel,
  RUN_FAILED: TERM_ICON.failed,
  ANNOUNCE: TERM_ICON.announcement,
  COMPLETE_OBJECTIVE: TERM_ICON.target,
  UNLOCK_MILESTONE: TERM_ICON.milestone,
  START_GAME: TERM_ICON.round,
  REMOVE_STACK: TERM_ICON.item,
  DISK_FULL: TERM_ICON.warning,
  MEMORY_FULL: TERM_ICON.warning,
  DM_NOT_DELIVERED: TERM_ICON.warning,
  ROLE_NOT_CHANGED: TERM_ICON.warning,
  ROLE_AMBIGUOUS: TERM_ICON.warning,
  ROLE_NOT_CREATED: TERM_ICON.warning,
  LOCK_NOT_KEPT: TERM_ICON.warning,
  LOCK_WITHOUT_CHANNEL: TERM_ICON.warning,
}

export function actionIcon(kind: string): Icon {
  return KIND_ICON[kind] ?? TERM_ICON.journal
}

export function ActionRow({ action, now }: { action?: Action; now: number }) {
  /**
   * A property access, so the chosen icon reads as an existing component and not as one defined in place.
   */
  const icons = { Icon: action ? actionIcon(action.kind) : TERM_ICON.journal }

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
