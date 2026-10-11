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

/** The signed-in person's picture, falling back to initials, since `discordAvatarUrl` is often not on record. */
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

/** The first letters of a name as written, not forced to capitals. */
export function initials(name: string | undefined): string {
  const words = (name ?? "").trim().split(/\s+/).filter(Boolean)
  if (words.length === 0) return "?"
  if (words.length === 1) return words[0].slice(0, 2)
  return words[0].slice(0, 1) + words[1].slice(0, 1)
}

/**
 * While a step-up holds: until when, and a control that ends it at once.
 *
 * Gone within one tick of `useNow` once it runs out; the server, not this row, is what refuses a change after.
 */
export function StepUpHolding({ until }: { until: string | undefined }) {
  const end = useEndStepUp()
  const now = useNow()
  if (!until || Date.parse(until) <= now) return null
  return (
    <div className="flex items-center gap-2 rounded-lg border border-border px-2.5 py-1.5">
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
  /** Inside an island, which already has the border. */
  plain?: boolean
}) {
  const [open, setOpen] = useState(false)
  const keys = useSecurityKeyActions(me)
  const app = useAuthenticatorActions(me)
  const notifications = useNotificationActions()

  /** The popover steps aside while a question is asked, and its dialogs mount outside it to outlive it. */
  const asking = keys.asking || app.asking || notifications.open
  if (asking && open) setOpen(false)

  return (
    <>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <button
            type="button"
            aria-label={me?.name ? t("steward.shell.account-of", { name: me.name }) : t("steward.shell.account")}
            className={`flex size-control shrink-0 items-center justify-center rounded-full transition-colors duration-150 ease-out focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none ${plain ? "hover:opacity-80" : "border border-border bg-card hover:border-input focus-visible:border-ring"}`}
          >
            <UserAvatar me={me} url={me?.discordAvatarUrl} className="size-8" />
          </button>
        </PopoverTrigger>

        {/* Never wider than the phone, so a long key name truncates rather than pushing its buttons off. */}
        <PopoverContent align={align} className="flex w-[min(20rem,calc(100vw-2rem))] flex-col gap-3">
          <div className="flex items-center gap-2">
            <UserAvatar me={me} url={me?.discordAvatarUrl} className="size-8" />
            <div className="flex min-w-0 flex-col">
              <span className="truncate text-sm font-medium">{me?.name ?? t("steward.shell.unknown")}</span>
              <span className="truncate font-mono text-xs text-muted-foreground">
                {t("steward.shell.discord", { id: me?.id ?? "\u2013" })}
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
