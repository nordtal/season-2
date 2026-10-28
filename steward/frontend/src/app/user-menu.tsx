import { GearIcon, LockOpenIcon, SignOutIcon } from "@phosphor-icons/react"
import { useState } from "react"

import { api } from "@/lib/api"
import { useEndStepUp } from "@/lib/queries"
import { useNow } from "@/lib/use-now"
import type { Me } from "@/lib/api"
import { NotificationsDialog, useNotificationActions } from "@/app/notifications"
import { AuthenticatorAppDialogs, AuthenticatorAppRow, useAuthenticatorActions } from "@/app/authenticator-app"
import { SecurityKeyDialogs, SecurityKeyList, useSecurityKeyActions } from "@/app/security-keys"
import { Button } from "@/components/ui/button"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { t } from "@/lib/texts"
import { NO_VALUE } from "@/lib/format"

export function UserAvatar({ me, url, className }: { me: Me | undefined; url?: string; className?: string }) {
  const [broken, setBroken] = useState(false)
  const size = className ?? "size-control"

  if (url && !broken) {
    return (
      // eslint-disable-next-line @next/next/no-img-element -- this is not Next.js
      <img src={url} alt="" onError={() => setBroken(true)} className={`${size} shrink-0 rounded-full object-cover`} />
    )
  }

  return (
    <span
      aria-hidden
      className={`${size} flex shrink-0 items-center justify-center rounded-full bg-secondary text-xs font-medium text-foreground`}
    >
      {initials(me?.name)}
    </span>
  )
}

export function initials(name: string | undefined): string {
  const words = (name ?? "").trim().split(/\s+/).filter(Boolean)
  if (words.length === 0) return "?"
  if (words.length === 1) return words[0].slice(0, 2)
  return words[0].slice(0, 1) + words[1].slice(0, 1)
}

/** The row disappears within one tick of `useNow` after the step-up runs out. */
export function StepUpHolding({ until }: { until: string | undefined }) {
  const end = useEndStepUp()
  const now = useNow()
  if (!until || Date.parse(until) <= now) return null
  return (
    <div className="flex items-center gap-2">
      <LockOpenIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
      <span className="min-w-0 flex-1 text-sm">{t("steward.keys.step-up-holds", { until })}</span>
      <Button type="button" variant="outline" size="sm" disabled={end.isPending} onClick={() => end.mutate()}>
        {t("steward.keys.end-step-up")}
      </Button>
    </div>
  )
}

export function UserMenu({
  me,
  align = "end",
  plain,
}: {
  me: Me | undefined
  align?: "start" | "end"
  /** Set when the control is inside an island, which already has the border. */
  plain?: boolean
}) {
  const [open, setOpen] = useState(false)
  const keys = useSecurityKeyActions(me)
  const app = useAuthenticatorActions(me)
  const notifications = useNotificationActions()

  /**
   * The popover closes while a question is asked. The dialogs are mounted outside it, so they outlive it.
   */
  const asking = keys.asking || app.asking || notifications.open
  if (asking && open) setOpen(false)

  return (
    <>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <button
            type="button"
            aria-label={me?.name ? t("steward.shell.account-of", { name: me.name }) : t("steward.shell.account")}
            className={`flex size-control shrink-0 items-center justify-center rounded-full transition-colors duration-150 ease-out focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none ${plain ? "hover:opacity-80" : "border border-border bg-card hover:border-input focus-visible:border-ring"}`}
          >
            <UserAvatar me={me} url={me?.discordAvatarUrl} className="size-8" />
          </button>
        </PopoverTrigger>

        {/* At most the phone's width, so a long key name is truncated and its buttons stay in view. */}
        <PopoverContent align={align} className="flex w-80 flex-col gap-3">
          <div className="flex items-center gap-2">
            <UserAvatar me={me} url={me?.discordAvatarUrl} className="size-8" />
            <div className="flex min-w-0 flex-col">
              <span className="truncate text-sm font-medium">{me?.name ?? t("steward.shell.unknown")}</span>
              <span className="truncate font-mono text-xs text-muted-foreground">
                {t("steward.shell.discord", { id: me?.id ?? NO_VALUE })}
              </span>
            </div>
          </div>

          <StepUpHolding until={me?.stepUpUntil} />

          <SecurityKeyList state={keys} />

          <AuthenticatorAppRow state={app} />

          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="justify-start"
            onClick={() => notifications.setOpen(true)}
          >
            <GearIcon aria-hidden />
            {t("steward.notifications.title")}
          </Button>

          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="justify-start"
            onClick={async () => {
              await api<void>("/auth/logout", { method: "POST" })
              window.location.assign("/")
            }}
          >
            <SignOutIcon aria-hidden />
            {t("steward.shell.sign-out")}
          </Button>
        </PopoverContent>
      </Popover>

      <SecurityKeyDialogs state={keys} />
      <AuthenticatorAppDialogs state={app} />
      <NotificationsDialog state={notifications} />
    </>
  )
}
