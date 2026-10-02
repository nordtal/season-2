import { ShieldSlashIcon, UserPlusIcon, WarningIcon } from "@phosphor-icons/react"
import type { ReactNode } from "react"
import { useState } from "react"
import { toast } from "sonner"

import type { Grant, Person } from "@/lib/api"
import { count, dateTime, playtime, relative, splitPlaytime } from "@/lib/format"
import {
  useGrantAccess,
  useEnforcePack,
  useExemptFromPack,
  useGrantAdmin,
  useGrants,
  useRevokeAccess,
  useRevokeAdmin,
  useSetPlaytime,
  useUnlink,
} from "@/lib/queries"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { Entity } from "@/components/steward/entity"
import { Stat } from "@/components/steward/stat"
import { StatusBadge, type Tone } from "@/components/steward/status"
import { QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import {
  ResponsiveDialogDescription,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Separator } from "@/components/ui/separator"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

/** What the Users page shows and changes about one person: the badges, the periods and every dialog. */

/** What to call somebody in a control's accessible name, in the order the table itself reads. */
export function personName(person: Person): string {
  return person.discordDisplayName ?? person.discordUsername ?? person.mcName ?? person.discordId
}

/** Guild membership; `MEMBER` is the quiet one, so LEFT and BANNED stand out. */
const MEMBER_STATES: Record<string, { label: string; tone: Tone; title: string }> = {
  MEMBER: { label: "Member", tone: "idle", title: "In the guild, as the bot last saw it." },
  LEFT: {
    label: "left",
    tone: "warn",
    title: "No longer in the guild. A purchased period keeps running regardless - it is not paused.",
  },
  BANNED: {
    label: "banned",
    tone: "down",
    title: "Banned in Discord. The login is refused while that holds; the paid period keeps expiring meanwhile.",
  },
}

export function MemberBadge({ state }: { state: string }) {
  const known = MEMBER_STATES[state]
  /** An unknown value is shown, not swallowed, so a state added later reads as new rather than empty. */
  if (!known) {
    return (
      <StatusBadge tone="idle" tipContent="This interface does not know this membership state.">
        {state}
      </StatusBadge>
    )
  }
  return (
    <StatusBadge tone={known.tone} tipContent={known.title}>
      {known.label}
    </StatusBadge>
  )
}

/** Three rows of nothing while a person's periods are read. */
const WAITING_GRANTS = [0, 1, 2]

const GRANT_SOURCES: Record<string, string> = {
  PURCHASE: "Purchase",
  ADMIN: "by hand",
}

/** Where a period stands right now, judged from the row itself rather than from the roster. */
function grantTone(grant: Grant, now: number): { label: string; tone: Tone; title: string } {
  if (grant.revoked) {
    return {
      label: `revoked ${dateTime(grant.revoked)}`,
      tone: "down",
      title: "A revoked period never counts, not even inside its own window.",
    }
  }
  const from = new Date(grant.validFrom).getTime()
  const until = new Date(grant.validUntil).getTime()
  if (from > now) {
    return {
      label: `begins ${relative(grant.validFrom, now)}`,
      tone: "idle",
      title: "Bought but not yet begun - a period is appended, never overwritten.",
    }
  }
  if (until > now) {
    return { label: "running", tone: "ok", title: "This period covers right now." }
  }
  return { label: "expired", tone: "idle", title: "This period lies entirely behind us." }
}

/** A uuid or a request id, short enough for a cell and complete in the title attribute. */
function shortId(value: string): string {
  return value.length > 8 ? `${value.slice(0, 8)}…` : value
}

/** A toast body naming the person it is about, next to a fixed label. */
function personToast(label: string, discordId: string): ReactNode {
  return (
    <span className="inline-flex min-w-0 items-center gap-1.5">
      {label}
      <Entity id={discordId} kind="discord" interactive={false} />
    </span>
  )
}

/** The longest grant in days, as `AccessApi.MOST_DAYS`. */
const MOST_DAYS = 365

/**
 * Granting, with the arithmetic named out loud.
 *
 * A new period is appended behind a running one; one bought before the SMP opens starts then.
 */
export function GrantDialog({
  person,
  open,
  onOpenChange,
}: {
  /** Prefills the id, for the row-level "Grant". */
  person?: Person
  /** When given, the dialog is controlled from outside and draws no trigger of its own. */
  open?: boolean
  onOpenChange?: (open: boolean) => void
} = {}) {
  const grant = useGrantAccess()
  const [discordId, setDiscordId] = useState(person?.discordId ?? "")
  const [days, setDays] = useState("30")
  const parsedDays = Number.parseInt(days, 10)
  const usable = discordId.trim().length > 0 && Number.isFinite(parsedDays) && parsedDays > 0 && parsedDays <= MOST_DAYS

  return (
    <AskThenAct
      open={open}
      onOpenChange={onOpenChange}
      trigger={
        open === undefined ? (
          <Button type="button">
            <UserPlusIcon aria-hidden />
            Grant access
          </Button>
        ) : null
      }
      title="Grant access by hand"
      description={
        <>
          The bot writes a period with the source <code className="text-xs">ADMIN</code> - no payment, no bunq tab -
          gives the role and tells the person by direct message.
        </>
      }
      action="Grant"
      disabled={!usable || grant.isPending}
      act={() => {
        grant.mutate(
          { discordId: discordId.trim(), days: parsedDays },
          {
            onSuccess: (written, asked) => {
              /** The person if opened from their row, otherwise the id that was typed. */
              toast.success(personToast("Access granted for", asked.discordId), {
                description: `Valid until ${dateTime(written.until)}. A journal line names you.`,
              })
              setDiscordId(person?.discordId ?? "")
            },
            onError: (error) => {
              toast.error("No access was granted", { description: String(error) })
            },
          },
        )
      }}
    >
      <div className="flex flex-col gap-3">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="grant-discord-id">Discord-ID</Label>
          <Input
            id="grant-discord-id"
            value={discordId}
            onChange={(event) => setDiscordId(event.target.value)}
            placeholder="e.g. 214906139328839681"
            className="font-mono"
            autoComplete="off"
            spellCheck={false}
            inputMode="numeric"
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="grant-days">Days</Label>
          <Input
            id="grant-days"
            value={days}
            onChange={(event) => setDays(event.target.value)}
            type="number"
            min={1}
            max={MOST_DAYS}
            className="w-32"
            aria-invalid={Number.isFinite(parsedDays) && parsedDays > MOST_DAYS}
          />
        </div>
        <ul className="flex list-disc flex-col gap-1 pl-4 text-sm text-muted-foreground">
          <li>At most {MOST_DAYS} days. A longer period is two grants.</li>
          <li>A day is exactly 24 hours, not a calendar day.</li>
          <li>
            If a period is already running, the new one is appended - paid time is never lost, and periods are never
            summed across a gap.
          </li>
          <li>If the SMP launch has not been reached, the period starts at that date and not today.</li>
          <li>
            If the bot does not know this Discord id yet, the account is created for it. A mistyped id therefore
            produces a person who does not exist - and no error.
          </li>
        </ul>
      </div>
    </AskThenAct>
  )
}

/**
 * Sets an account's total play time by hand, the one lever on the prestige tier.
 *
 * Hours and minutes over range are carried, not refused; the wire carries seconds.
 */

/** An empty field is zero; anything else that is not a number is NaN, which `usable` catches. */
function parseField(value: string): number {
  return Number(value.replace(",", "."))
}

export function PlaytimeDialog({
  person,
  open,
  onOpenChange,
}: {
  person: Person
  open?: boolean
  onOpenChange?: (open: boolean) => void
}) {
  const write = useSetPlaytime()
  const start = splitPlaytime(person.playtimeSeconds ?? 0)
  const [days, setDays] = useState(String(start.days))
  const [hours, setHours] = useState(String(start.hours))
  const [minutes, setMinutes] = useState(String(start.minutes))

  const parts = [parseField(days), parseField(hours), parseField(minutes)]
  const usable = parts.every((part) => Number.isFinite(part) && part >= 0)
  const seconds = usable ? Math.round(parts[0] * 86_400 + parts[1] * 3_600 + parts[2] * 60) : 0

  return (
    <AskThenAct
      open={open}
      onOpenChange={onOpenChange}
      title="Set play time"
      description={
        <>
          Replaces the counted total for {personName(person)}. The prestige tier follows from it, and there is nothing
          else to set.
        </>
      }
      action="Save"
      disabled={!usable || write.isPending}
      act={() => {
        write.mutate(
          { discordId: person.discordId, seconds },
          {
            onSuccess: () => {
              toast.success(`Play time set for ${personName(person)}`, {
                description: `${playtime(seconds)} from now on. A journal line names you.`,
              })
            },
            onError: (error) => {
              toast.error("The play time was not written", { description: String(error) })
            },
          },
        )
      }}
    >
      <div className="flex flex-col gap-1.5">
        {/* Three columns even on a phone, since the three are one number read left to right. */}
        <div className="grid grid-cols-3 gap-2">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="playtime-days">Days</Label>
            <Input
              id="playtime-days"
              value={days}
              onChange={(event) => setDays(event.target.value)}
              type="number"
              min={0}
              inputMode="numeric"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="playtime-hours">Hours</Label>
            <Input
              id="playtime-hours"
              value={hours}
              onChange={(event) => setHours(event.target.value)}
              type="number"
              min={0}
              inputMode="numeric"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="playtime-minutes">Minutes</Label>
            <Input
              id="playtime-minutes"
              value={minutes}
              onChange={(event) => setMinutes(event.target.value)}
              type="number"
              min={0}
              inputMode="numeric"
            />
          </div>
        </div>
        <p className="text-xs text-muted-foreground">
          Counted so far: {playtime(person.playtimeSeconds ?? undefined)}
          {usable ? `, becoming ${playtime(seconds)}` : null}. Anybody online while this is written keeps counting up
          from the new value.
        </p>
      </div>
    </AskThenAct>
  )
}

/** Unlinks the Minecraft account; the Discord account keeps its access, and the bot tells the person. */
export function UnlinkDialog({
  person,
  open,
  onOpenChange,
}: {
  person: Person
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const unlink = useUnlink()
  return (
    <AskThenAct
      open={open}
      onOpenChange={onOpenChange}
      title="Unlink?"
      description={
        <>
          Breaks the link between this Discord account and its Minecraft account. The paid period is untouched; the
          person can link a Minecraft account again afterwards.
        </>
      }
      action="Unlink"
      destructive
      disabled={unlink.isPending}
      act={() => {
        unlink.mutate(person.discordId, {
          onSuccess: (result) => {
            if (!result.unlinked) {
              toast.warning("There was nothing to unlink", {
                description: personToast("No Minecraft account was linked to", person.discordId),
              })
              return
            }
            toast.success(personToast("Unlinked", person.discordId), {
              description: "A journal line names you.",
            })
          },
          onError: (error) => {
            toast.error("Nothing was unlinked", { description: String(error) })
          },
        })
      }}
    />
  )
}

/** Lets one player through without the resource pack, or takes that back. */
export function PackExemptionDialog({
  person,
  open,
  onOpenChange,
}: {
  person: Person
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const exempt = useExemptFromPack()
  const enforce = useEnforcePack()
  const exempted = Boolean(person.packExemptAt)
  const change = exempted ? enforce : exempt
  return (
    <AskThenAct
      open={open}
      onOpenChange={onOpenChange}
      title={
        <>
          {exempted
            ? `Require the resource pack for ${personName(person)} again?`
            : `Let ${personName(person)} play without the resource pack?`}
        </>
      }
      description={
        exempted
          ? "From their next login on, they get the pack like everybody else."
          : "From their next login on, the network sends them no pack, until an admin requires it again. Their own local pack then shows."
      }
      action={exempted ? "Enforce resource pack" : "Skip resource pack"}
      disabled={change.isPending}
      act={() => {
        change.mutate(person.discordId, {
          onSuccess: () => {
            toast.success(personToast(exempted ? "Resource pack required" : "Resource pack skipped", person.discordId))
          },
          onError: (error) => {
            toast.error("Nothing was changed", { description: String(error) })
          },
        })
      }}
    />
  )
}

/** Makes a member an admin below the signed-in one. The Discord admin role follows. */
export function MakeAdminDialog({
  person,
  open,
  onOpenChange,
}: {
  person: Person
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const grant = useGrantAdmin()
  return (
    <AskThenAct
      open={open}
      onOpenChange={onOpenChange}
      title={<>Make {personName(person)} an admin?</>}
      description="Below you. Only you and the admins above you can revoke it."
      action="Make admin"
      disabled={grant.isPending}
      act={() => {
        grant.mutate(person.discordId, {
          onSuccess: () => {
            toast.success(personToast("Admin", person.discordId))
          },
          onError: (error) => {
            toast.error("Nobody was made an admin", { description: String(error) })
          },
        })
      }}
    />
  )
}

/** Takes admin from somebody below the signed-in one, and from their whole branch. */
export function RevokeAdminDialog({
  person,
  branch,
  open,
  onOpenChange,
}: {
  person: Person
  /** How many admins sit below this one and go with them. */
  branch: number
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const revoke = useRevokeAdmin()
  return (
    <AskThenAct
      open={open}
      onOpenChange={onOpenChange}
      title={<>Revoke admin from {personName(person)}?</>}
      description={
        <>
          {branch === 0
            ? "Their open sessions end."
            : `${count(branch)} ${branch === 1 ? "admin" : "admins"} below them lose it too. Every open session of theirs ends.`}
        </>
      }
      action="Revoke admin"
      destructive
      disabled={revoke.isPending}
      act={() => {
        revoke.mutate(person.discordId, {
          onSuccess: (result) => {
            toast.success(
              personToast("No longer admin", person.discordId),
              result.removed.length > 1
                ? { description: `${count(result.removed.length - 1)} below them as well.` }
                : undefined,
            )
          },
          onError: (error) => {
            toast.error("Nothing was revoked", { description: String(error) })
          },
        })
      }}
    />
  )
}

/**
 * Revokes every running period; the person dialog closes first, so there are never two focus traps.
 *
 * @param open when given, the dialog is controlled from outside and draws no trigger of its own
 */
export function RevokeDialog({
  person,
  open,
  onOpenChange,
}: {
  person: Person
  open?: boolean
  onOpenChange?: (open: boolean) => void
}) {
  const revoke = useRevokeAccess()

  return (
    <AskThenAct
      open={open}
      onOpenChange={onOpenChange}
      trigger={
        open === undefined ? (
          <Button type="button" variant="ghost" size="sm" className="text-destructive">
            <ShieldSlashIcon aria-hidden />
            Revoke
          </Button>
        ) : null
      }
      title="Revoke access?"
      description={
        <>
          What is revoked is the <span className="text-foreground">whole remaining run</span> of the person this row
          names - every period not yet expired at once, not a single one.
        </>
      }
      action="Revoke"
      destructive
      disabled={revoke.isPending}
      act={() => {
        revoke.mutate(person.discordId, {
          onSuccess: (result) => {
            /** Zero means the run had already ended or somebody else revoked it. */
            if (result.revoked === 0) {
              toast.warning("There was nothing to revoke", {
                description: personToast("No period was still running for", person.discordId),
              })
              return
            }
            toast.success(personToast(`${count(result.revoked)} period(s) revoked for`, person.discordId), {
              description: "A journal line names you.",
            })
          },
          onError: (error) => {
            toast.error("Nothing was revoked", { description: String(error) })
          },
        })
      }}
    >
      <div className="flex flex-col gap-3 text-sm">
        <p className="flex items-start gap-2 rounded-md border border-warning/30 bg-warning/8 px-3 py-2 text-warning">
          <WarningIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
          Anyone playing right now is thrown out: the proxy re-checks every connected player's access regularly and
          disconnects as soon as it no longer holds - not only at the next login.
        </p>
        <p className="text-muted-foreground">
          Paid time does not come back this way. A later grant starts fresh and does not credit the revoked remainder.
        </p>
        <p className="text-muted-foreground">
          The entry stays and is only marked revoked - which is why a date still stands beside "no access" in the list,
          instead of the person looking like a stranger.
        </p>
      </div>
    </AskThenAct>
  )
}

/** One person's chain, period by period; the writes live in the table row. */
export function PersonGrants({ person, now, onRevoke }: { person: Person; now: number; onRevoke: () => void }) {
  const grants = useGrants(person.discordId)

  return (
    <>
      <ResponsiveDialogHeader>
        <ResponsiveDialogTitle className="flex flex-wrap items-center justify-between gap-3 pr-6">
          <Entity id={person.discordId} kind="discord" />
          {person.accessActive ? (
            <Button type="button" variant="outline" size="sm" className="text-destructive" onClick={onRevoke}>
              <ShieldSlashIcon aria-hidden />
              Revoke
            </Button>
          ) : null}
        </ResponsiveDialogTitle>
        <ResponsiveDialogDescription>
          Request → tab → paid → access → linked. This is the fourth link: every period, its source and - for a purchase
          - the payment request it came from.
        </ResponsiveDialogDescription>
      </ResponsiveDialogHeader>

      <div className="flex flex-wrap gap-6">
        <Stat label="Guild" value={<MemberBadge state={person.memberState} />} />
        <Stat
          label="Minecraft"
          value={person.minecraftUuid ? <Entity id={person.minecraftUuid} kind="minecraft" /> : "\u2013"}
          hint={person.linked ? `linked ${dateTime(person.linked)}` : "not linked"}
        />
        <Stat label="Language" value={person.locale} hint={`last changed ${relative(person.updated, now)}`} />
      </div>

      <Separator />

      <QueryState
        query={grants}
        empty={{
          title: "No period",
          note: "None has ever been written for this account - neither bought nor by hand.",
        }}
        isEmpty={(list: Grant[]) => list.length === 0}
      >
        {(list) => (
          <Table className="steward-table">
            <TableHeader>
              <TableRow>
                <TableHead className="w-[7rem]">Source</TableHead>
                <TableHead>Window</TableHead>
                <TableHead className="w-[13rem]">State</TableHead>
                <TableHead className="w-[8rem]">Request</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list === undefined
                ? WAITING_GRANTS.map((index) => (
                    <TableRow key={index}>
                      <TableCell data-label="Source">
                        <SkeletonText width="medium" />
                      </TableCell>
                      <TableCell data-label="Window">
                        <SkeletonText width="long" />
                      </TableCell>
                      <TableCell data-label="State">
                        <Skeleton className="h-5 w-24 rounded-full" />
                      </TableCell>
                      <TableCell data-label="Request">
                        <SkeletonText width="short" />
                      </TableCell>
                    </TableRow>
                  ))
                : list.map((row) => {
                    const state = grantTone(row, now)
                    return (
                      <TableRow key={row.id}>
                        <TableCell data-label="Source">{GRANT_SOURCES[row.source] ?? row.source}</TableCell>
                        <TableCell data-label="Window" className="text-muted-foreground tnum">
                          {dateTime(row.validFrom)}
                          {" \u2013 "}
                          {dateTime(row.validUntil)}
                        </TableCell>
                        <TableCell data-label="State">
                          <StatusBadge tone={state.tone} tipContent={state.title}>
                            {state.label}
                          </StatusBadge>
                        </TableCell>
                        <TableCell data-label="Request">
                          {row.paymentRequestId ? (
                            <span className="font-mono text-xs" title={row.paymentRequestId}>
                              {shortId(row.paymentRequestId)}
                            </span>
                          ) : (
                            <span
                              className="text-xs text-muted-foreground"
                              title={
                                row.source === "PURCHASE"
                                  ? "Bought, but the payment request is no longer in the database - it is set to NULL when the request is deleted."
                                  : "Granted by hand, so there is no payment request."
                              }
                            >
                              {"\u2013"}
                            </span>
                          )}
                        </TableCell>
                      </TableRow>
                    )
                  })}
            </TableBody>
          </Table>
        )}
      </QueryState>
    </>
  )
}
