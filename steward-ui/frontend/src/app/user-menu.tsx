import { SignOutIcon } from "@phosphor-icons/react"
import { useState } from "react"

import { api } from "@/lib/api"
import type { Me } from "@/lib/api"
import { SecurityKeyDialogs, SecurityKeyList, useSecurityKeyActions } from "@/app/security-keys"
import { Button } from "@/components/ui/button"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"

/**
 * Who is signed in, and everything that used to be the settings page's account half (steward/89).
 *
 * Till, 2026-09-17: the picture of the signed-in person stands level with the island, a tap opens
 * the user settings as a popover, and the popover is minimal without being unhelpful. What is in it
 * is the whole of it - name, Discord id, the keys as a list, and the way out - because the page
 * that used to hold those is the page this replaces.
 *
 * **The picture never disappears, even when there is none.** A way to sign out that is there most
 * of the time is worse than one that is plain, so an account with no picture on record gets the
 * first letters of its name on a quiet round surface and the same tap target.
 *
 * **The picture is not always on record, and that is not an error** (steward/91). `whoAmI` in
 * `StewardUi.java` reads `discordAvatarUrl` from the same `person` row `/api/people` answers, over
 * the session's Discord id - not a second call to Discord, and not `usePeople()` mounted in the
 * shell for one 32px image (that query has a 30s `staleTime` and loads the whole access list). An
 * account signed into Steward without ever having been mirrored into that list, same as one Discord
 * itself has no picture for, answers with the field simply absent - the initials fallback below is
 * that same case, not a degraded one.
 */
export function UserAvatar({
  me,
  url,
  className,
}: {
  me: Me | undefined
  url?: string
  className?: string
}) {
  const [broken, setBroken] = useState(false)
  const size = className ?? "size-control"

  if (url && !broken) {
    return (
      // eslint-disable-next-line @next/next/no-img-element -- this is not Next.js
      <img
        src={url}
        alt=""
        onError={() => setBroken(true)}
        className={`${size} shrink-0 rounded-full object-cover`}
      />
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

/**
 * The first letters of a name, exactly as they are written.
 *
 * Not forced to capitals: half of this interface is lowercase on purpose - the service names, the
 * file names - and a name that is typed in lowercase is a name, not a mistake to correct.
 */
export function initials(name: string | undefined): string {
  const words = (name ?? "").trim().split(/\s+/).filter(Boolean)
  if (words.length === 0) return "?"
  if (words.length === 1) return words[0].slice(0, 2)
  return words[0].slice(0, 1) + words[1].slice(0, 1)
}

export function UserMenu({
  me,
  align = "end",
  plain,
}: {
  me: Me | undefined
  align?: "start" | "end"
  /** Inside an island, where a border of its own would be a border inside a border. */
  plain?: boolean
}) {
  const [open, setOpen] = useState(false)
  const keys = useSecurityKeyActions(me)

  // The popover gets out of the way as soon as one of the three questions is asked. The dialogs
  // themselves are mounted below, outside it: a dialog rendered inside a popover is unmounted by
  // the first outside click, and a dialog's own overlay is an outside click.
  const asking = keys.asking
  if (asking && open) setOpen(false)

  return (
    <>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <button
            type="button"
            aria-label={me?.name ? `Account of ${me.name}` : "Account"}
            className={`flex size-control shrink-0 items-center justify-center rounded-full transition-colors duration-150 ease-out focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none ${plain ? "hover:opacity-80" : "border border-border bg-card hover:border-input focus-visible:border-ring"}`}
          >
            <UserAvatar me={me} url={me?.discordAvatarUrl} className="size-8" />
          </button>
        </PopoverTrigger>

        {/*
          Wider than the default popover and never wider than the phone: the key names are the one
          thing in here that can be long, and they truncate rather than pushing the two buttons
          beside them off the edge.
        */}
        <PopoverContent
          align={align}
          className="flex w-[min(20rem,calc(100vw-2rem))] flex-col gap-3"
        >
          <div className="flex items-center gap-2">
            <UserAvatar me={me} url={me?.discordAvatarUrl} className="size-8" />
            <div className="flex min-w-0 flex-col">
              <span className="truncate text-sm font-medium">{me?.name ?? "unknown"}</span>
              <span className="truncate font-mono text-xs text-muted-foreground">
                Discord {me?.id ?? "–"}
              </span>
            </div>
          </div>

          <SecurityKeyList state={keys} />

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
            Sign out
          </Button>
        </PopoverContent>
      </Popover>

      <SecurityKeyDialogs state={keys} />
    </>
  )
}
