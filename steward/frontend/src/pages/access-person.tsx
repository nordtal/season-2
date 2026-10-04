import { ShieldSlashIcon, UserPlusIcon, WarningIcon } from "@phosphor-icons/react"
import type { ReactNode } from "react"
import { useState } from "react"
import { toast } from "sonner"

import type { Grant, Person } from "@/lib/api"
import { dateTime, playtime, splitPlaytime } from "@/lib/format"
import { choice, t } from "@/lib/texts"
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
const MEMBER_TONES: Record<string, Tone> = { LEFT: "warn", BANNED: "down" }

export function MemberBadge({ state }: { state: string }) {
  const tip = t("steward.people.member-tip", { state: choice(state) })
  /** An unknown value is shown, not swallowed, so a state added later reads as new rather than empty. */
  return (
    <StatusBadge tone={MEMBER_TONES[state] ?? "idle"} tipContent={tip}>
      {t("steward.people.member", { state: choice(state) }) || state}
    </StatusBadge>
  )
}

/** Three rows of nothing while a person's periods are read. */
const WAITING_GRANTS = [0, 1, 2]

/** Where a period stands right now, judged from the row itself rather than from the roster. */
function grantTone(grant: Grant, now: number): { label: string; tone: Tone; title: string } {
  if (grant.revoked) {
    return {
      label: t("steward.people.revoked", { at: grant.revoked }),
      tone: "down",
      title: t("steward.people.revoked-tip"),
    }
  }
  const from = new Date(grant.validFrom).getTime()
  const until = new Date(grant.validUntil).getTime()
  if (from > now) {
    return {
      label: t("steward.people.begins", { at: grant.validFrom }),
      tone: "idle",
      title: t("steward.people.begins-tip"),
    }
  }
  if (until > now) {
    return { label: t("steward.people.running"), tone: "ok", title: t("steward.people.running-tip") }
  }
  return { label: t("steward.people.over"), tone: "idle", title: t("steward.people.over-tip") }
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
            {t("steward.people.grant-access")}
          </Button>
        ) : null
      }
      title={t("steward.people.grant-title")}
      description={t("steward.people.grant-note")}
      action={t("steward.people.grant")}
      disabled={!usable || grant.isPending}
      act={() => {
        grant.mutate(
          { discordId: discordId.trim(), days: parsedDays },
          {
            onSuccess: (written, asked) => {
              /** The person if opened from their row, otherwise the id that was typed. */
              toast.success(personToast(t("steward.people.granted"), asked.discordId), {
                description: t("steward.people.valid-until", { until: written.until }),
              })
              setDiscordId(person?.discordId ?? "")
            },
            onError: (error) => {
              toast.error(t("steward.people.not-granted"), { description: String(error) })
            },
          },
        )
      }}
    >
      <div className="flex flex-col gap-3">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="grant-discord-id">{t("steward.people.discord-id")}</Label>
          <Input
            id="grant-discord-id"
            value={discordId}
            onChange={(event) => setDiscordId(event.target.value)}
            placeholder={t("steward.people.id-example")}
            className="font-mono"
            autoComplete="off"
            spellCheck={false}
            inputMode="numeric"
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="grant-days">{t("steward.form.days")}</Label>
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
          <li>{t("steward.people.most-days", { most: MOST_DAYS })}</li>
          <li>{t("steward.people.day-is-day")}</li>
          <li>{t("steward.people.appended")}</li>
          <li>{t("steward.people.from-launch")}</li>
          <li>{t("steward.people.unknown-id")}</li>
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
      title={t("steward.people.playtime-title")}
      description={t("steward.people.playtime-note", { name: personName(person) })}
      action={t("steward.form.save")}
      disabled={!usable || write.isPending}
      act={() => {
        write.mutate(
          { discordId: person.discordId, seconds },
          {
            onSuccess: () => {
              toast.success(t("steward.people.playtime-set", { name: personName(person) }), {
                description: t("steward.people.playtime-from", { time: playtime(seconds) }),
              })
            },
            onError: (error) => {
              toast.error(t("steward.people.playtime-not-written"), { description: String(error) })
            },
          },
        )
      }}
    >
      <div className="flex flex-col gap-1.5">
        {/* Three columns even on a phone, since the three are one number read left to right. */}
        <div className="grid grid-cols-3 gap-2">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="playtime-days">{t("steward.form.days")}</Label>
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
            <Label htmlFor="playtime-hours">{t("steward.people.hours")}</Label>
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
            <Label htmlFor="playtime-minutes">{t("steward.people.minutes")}</Label>
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
          {t("steward.people.counted", {
            counted: playtime(person.playtimeSeconds ?? undefined),
            becoming: playtime(seconds),
            usable,
          })}
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
      title={t("steward.people.unlink-title")}
      description={t("steward.people.unlink-note")}
      action={t("steward.people.unlink")}
      destructive
      disabled={unlink.isPending}
      act={() => {
        unlink.mutate(person.discordId, {
          onSuccess: (result) => {
            if (!result.unlinked) {
              toast.warning(t("steward.people.nothing-to-unlink"), {
                description: personToast(t("steward.people.none-linked"), person.discordId),
              })
              return
            }
            toast.success(personToast(t("steward.people.unlinked"), person.discordId), {
              description: t("steward.people.journal-names-you"),
            })
          },
          onError: (error) => {
            toast.error(t("steward.people.not-unlinked"), { description: String(error) })
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
      title={t("steward.people.pack-title", { exempted, name: personName(person) })}
      description={t("steward.people.pack-note", { exempted })}
      action={t("steward.people.pack", { exempted })}
      disabled={change.isPending}
      act={() => {
        change.mutate(person.discordId, {
          onSuccess: () => {
            toast.success(personToast(t("steward.people.pack-changed", { exempted }), person.discordId))
          },
          onError: (error) => {
            toast.error(t("steward.people.nothing-changed"), { description: String(error) })
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
      title={t("steward.people.make-admin-title", { name: personName(person) })}
      description={t("steward.people.make-admin-note")}
      action={t("steward.people.make-admin")}
      disabled={grant.isPending}
      act={() => {
        grant.mutate(person.discordId, {
          onSuccess: () => {
            toast.success(personToast(t("steward.people.admin"), person.discordId))
          },
          onError: (error) => {
            toast.error(t("steward.people.not-made-admin"), { description: String(error) })
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
      title={t("steward.people.revoke-admin-title", { name: personName(person) })}
      description={t("steward.people.revoke-admin-note", { branch })}
      action={t("steward.people.revoke-admin")}
      destructive
      disabled={revoke.isPending}
      act={() => {
        revoke.mutate(person.discordId, {
          onSuccess: (result) => {
            toast.success(
              personToast(t("steward.people.no-longer-admin"), person.discordId),
              result.removed.length > 1
                ? { description: t("steward.people.below-too", { count: result.removed.length - 1 }) }
                : undefined,
            )
          },
          onError: (error) => {
            toast.error(t("steward.people.not-revoked"), { description: String(error) })
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
            {t("steward.people.revoke")}
          </Button>
        ) : null
      }
      title={t("steward.people.revoke-title")}
      description={t("steward.people.revoke-note")}
      action={t("steward.people.revoke")}
      destructive
      disabled={revoke.isPending}
      act={() => {
        revoke.mutate(person.discordId, {
          onSuccess: (result) => {
            /** Zero means the run had already ended or somebody else revoked it. */
            if (result.revoked === 0) {
              toast.warning(t("steward.people.nothing-to-revoke"), {
                description: personToast(t("steward.people.none-running"), person.discordId),
              })
              return
            }
            toast.success(personToast(t("steward.people.revoked-for", { count: result.revoked }), person.discordId), {
              description: t("steward.people.journal-names-you"),
            })
          },
          onError: (error) => {
            toast.error(t("steward.people.not-revoked"), { description: String(error) })
          },
        })
      }}
    >
      <div className="flex flex-col gap-3 text-sm">
        <p className="flex items-start gap-2 rounded-md border border-warning/30 bg-warning/8 px-3 py-2 text-warning">
          <WarningIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
          {t("steward.people.thrown-out")}
        </p>
        <p className="text-muted-foreground">{t("steward.people.no-refund")}</p>
        <p className="text-muted-foreground">{t("steward.people.entry-stays")}</p>
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
              {t("steward.people.revoke")}
            </Button>
          ) : null}
        </ResponsiveDialogTitle>
        <ResponsiveDialogDescription>{t("steward.people.chain")}</ResponsiveDialogDescription>
      </ResponsiveDialogHeader>

      <div className="flex flex-wrap gap-6">
        <Stat label={t("steward.people.guild")} value={<MemberBadge state={person.memberState} />} />
        <Stat
          label={t("steward.people.minecraft")}
          value={person.minecraftUuid ? <Entity id={person.minecraftUuid} kind="minecraft" /> : "\u2013"}
          hint={person.linked ? t("steward.people.linked-at", { at: person.linked }) : t("steward.people.not-linked")}
        />
        <Stat
          label={t("steward.people.language")}
          value={person.locale}
          hint={t("steward.people.last-changed", { at: person.updated })}
        />
      </div>

      <Separator />

      <QueryState
        query={grants}
        empty={{
          title: t("steward.people.no-period"),
          note: t("steward.people.no-period-note"),
        }}
        isEmpty={(list: Grant[]) => list.length === 0}
      >
        {(list) => (
          <Table className="steward-table">
            <TableHeader>
              <TableRow>
                <TableHead className="w-[7rem]">{t("steward.people.source-column")}</TableHead>
                <TableHead>{t("steward.people.window")}</TableHead>
                <TableHead className="w-[13rem]">{t("steward.people.state")}</TableHead>
                <TableHead className="w-[8rem]">{t("steward.people.request")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list === undefined
                ? WAITING_GRANTS.map((index) => (
                    <TableRow key={index}>
                      <TableCell data-label={t("steward.people.source-column")}>
                        <SkeletonText width="medium" />
                      </TableCell>
                      <TableCell data-label={t("steward.people.window")}>
                        <SkeletonText width="long" />
                      </TableCell>
                      <TableCell data-label={t("steward.people.state")}>
                        <Skeleton className="h-5 w-24 rounded-full" />
                      </TableCell>
                      <TableCell data-label={t("steward.people.request")}>
                        <SkeletonText width="short" />
                      </TableCell>
                    </TableRow>
                  ))
                : list.map((row) => {
                    const state = grantTone(row, now)
                    return (
                      <TableRow key={row.id}>
                        <TableCell data-label={t("steward.people.source-column")}>
                          {t("steward.people.source", { source: choice(row.source) }) || row.source}
                        </TableCell>
                        <TableCell data-label={t("steward.people.window")} className="text-muted-foreground tnum">
                          {dateTime(row.validFrom)}
                          {" \u2013 "}
                          {dateTime(row.validUntil)}
                        </TableCell>
                        <TableCell data-label={t("steward.people.state")}>
                          <StatusBadge tone={state.tone} tipContent={state.title}>
                            {state.label}
                          </StatusBadge>
                        </TableCell>
                        <TableCell data-label={t("steward.people.request")}>
                          {row.paymentRequestId ? (
                            <span className="font-mono text-xs" title={row.paymentRequestId}>
                              {shortId(row.paymentRequestId)}
                            </span>
                          ) : (
                            <span
                              className="text-xs text-muted-foreground"
                              title={t("steward.people.request-gone", { source: choice(row.source) })}
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
