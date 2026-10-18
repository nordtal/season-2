import { TERM_ICON } from "@/components/steward/term-icons"
import type { ReactNode } from "react"
import { useState } from "react"
import { toast } from "sonner"

import { adminsBelow } from "@/lib/admin-tree"
import type { Person } from "@/lib/api"
import { splitPlaytime } from "@/lib/format"
import { choice, t } from "@/lib/texts"
import {
  useGrantAccess,
  useEnforcePack,
  useExemptFromPack,
  useGrantAdmin,
  useMe,
  usePeople,
  useRevokeAccess,
  useRevokeAdmin,
  useSetPlaytime,
  useUnlink,
} from "@/lib/queries"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { Entity } from "@/components/steward/entity"
import { RowActions, type RowAction } from "@/components/steward/row-actions"
import { StatusBadge, type Tone } from "@/components/steward/status"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"

/** Returns what to call somebody in a control's accessible name. */
export function personName(person: Person): string {
  return person.discordDisplayName ?? person.discordUsername ?? person.mcName ?? person.discordId
}

/** `MEMBER` is the quiet one, so LEFT and BANNED stand out. */
const MEMBER_TONES: Record<string, Tone> = { LEFT: "warn", BANNED: "down" }

/**
 * A purchased period keeps running when the member leaves. A ban refuses the login while the paid period runs out.
 */
export function MemberBadge({ state }: { state: string }) {
  /** An unknown value is shown, so a state added later reads as new and not as empty. */
  return (
    <StatusBadge tone={MEMBER_TONES[state] ?? "idle"}>
      {t("steward.people.member", { state: choice(state) }) || state}
    </StatusBadge>
  )
}

function personToast(label: string, discordId: string): ReactNode {
  return (
    <span className="inline-flex min-w-0 items-center gap-1.5">
      {label}
      <Entity id={discordId} kind="discord" interactive={false} />
    </span>
  )
}

const MOST_DAYS = 365

/**
 * Grants access. The bot writes a period with the source ADMIN, gives the role and tells the person. A period is appended behind a running one and starts no earlier than the SMP launch. An unknown id makes an account.
 */
export function GrantDialog({
  person,
  open,
  onOpenChange,
}: {
  person?: Person
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
            <TERM_ICON.grantAccess aria-hidden />
            {t("steward.people.grant-access")}
          </Button>
        ) : null
      }
      title={t("steward.people.grant-title")}
      action={t("steward.people.grant")}
      disabled={!usable || grant.isPending}
      act={() => {
        grant.mutate(
          { discordId: discordId.trim(), days: parsedDays },
          {
            onSuccess: (written, asked) => {
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
        {Number.isFinite(parsedDays) && parsedDays > MOST_DAYS ? (
          <p className="text-sm text-destructive">{t("steward.people.most-days", { most: MOST_DAYS })}</p>
        ) : null}
      </div>
    </AskThenAct>
  )
}

/**
 * Sets an account's total play time by hand, the one lever on the prestige tier. Hours and minutes over their range are carried, not refused.
 */

/** An empty field is zero. Anything else that is not a number is NaN, which `usable` catches. */
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
      action={t("steward.form.save")}
      disabled={!usable || write.isPending}
      act={() => {
        write.mutate(
          { discordId: person.discordId, seconds },
          {
            onSuccess: () => {
              toast.success(t("steward.people.playtime-set", { name: personName(person) }), {
                description: t("steward.people.playtime-from", { time: seconds }),
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
        {/* Three columns even on a phone, because the three read as one number from left to right. */}
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
            counted: person.playtimeSeconds ?? 0,
            becoming: seconds,
            usable,
          })}
        </p>
      </div>
    </AskThenAct>
  )
}

/** Unlinks the Minecraft account. The paid period stays, and the person may link again. */
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
            toast.success(personToast(t("steward.people.unlinked"), person.discordId))
          },
          onError: (error) => {
            toast.error(t("steward.people.not-unlinked"), { description: String(error) })
          },
        })
      }}
    />
  )
}

/**
 * Lets one player through without the resource pack from their next login on, or takes that back.
 */
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

/**
 * Makes a member an admin below the signed-in one. Only that admin and those above can revoke it.
 */
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

/** Takes admin from somebody below the signed-in one, and their whole branch. */
export function RevokeAdminDialog({
  person,
  branch,
  open,
  onOpenChange,
}: {
  person: Person
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
 * Revokes every running period. The person dialog closes first, so there are never two focus traps. The proxy re-checks connected players, so revoking throws them out.
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
            <TERM_ICON.revokeAccess aria-hidden />
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
            /** Zero means that the run had already ended or somebody else revoked it. */
            if (result.revoked === 0) {
              toast.warning(t("steward.people.nothing-to-revoke"), {
                description: personToast(t("steward.people.none-running"), person.discordId),
              })
              return
            }
            toast.success(personToast(t("steward.people.revoked-for", { count: result.revoked }), person.discordId))
          },
          onError: (error) => {
            toast.error(t("steward.people.not-revoked"), { description: String(error) })
          },
        })
      }}
    >
      <p className="flex items-start gap-2 rounded-md border border-warning/30 bg-warning/8 px-3 py-2 text-sm text-warning">
        <TERM_ICON.warning className="mt-0.5 size-4 shrink-0" aria-hidden />
        {t("steward.people.thrown-out")}
      </p>
    </AskThenAct>
  )
}

/**
 * Shows `accessActive` (the login decision) and `accessUntil` (the latest period, revoked ones included), so revoked access does not look like none ever bought.
 */
export function AccessBadge({ person, now }: { person: Person; now: number }) {
  const until = person.accessUntil ? new Date(person.accessUntil).getTime() : null

  if (person.accessActive) {
    return (
      <StatusBadge tone="ok" tipContent={t("steward.people.exact-tip", { at: person.accessUntil ?? "" })}>
        {t("steward.people.active-until", { until: person.accessUntil ?? "" })}
      </StatusBadge>
    )
  }
  if (until === null) {
    return <StatusBadge tone="idle">{t("steward.people.never")}</StatusBadge>
  }
  if (until > now) {
    return (
      <StatusBadge tone="down" tipContent={t("steward.people.no-access-tip", { until: person.accessUntil ?? "" })}>
        {t("steward.people.no-access")}
      </StatusBadge>
    )
  }
  return (
    <StatusBadge tone="idle" tipContent={t("steward.people.exact-tip", { at: person.accessUntil ?? "" })}>
      {t("steward.people.expired", { at: person.accessUntil ?? "" })}
    </StatusBadge>
  )
}

/** Warns about the one state with no face to draw: paid, but no Minecraft account linked. */
export function LinkBadge({ person }: { person: Person }) {
  return (
    <StatusBadge
      tone={person.accessActive ? "warn" : "idle"}
      tipContent={t("steward.people.not-linked-tip", { paid: person.accessActive })}
    >
      {t("steward.people.not-linked")}
    </StatusBadge>
  )
}

/**
 * Shows the roles a person holds as badges, and a dash for none. The supporter role is given once and never taken away.
 */
export function RoleBadges({
  person,
  people,
  className,
}: {
  person: Person
  people: readonly Person[]
  className?: string
}) {
  return (
    <div className={"flex flex-wrap items-center gap-1 " + (className ?? "")}>
      {person.donor ? <StatusBadge tone="idle">{t("steward.people.supporter")}</StatusBadge> : null}
      {person.admin ? (
        <StatusBadge tone="idle" tipContent={grantedByText(person, people)}>
          {t("steward.people.admin")}
        </StatusBadge>
      ) : null}
      {person.packExemptAt ? (
        <StatusBadge tone="warn" tipContent={packExemptText(person, people)}>
          {t("steward.people.no-pack")}
        </StatusBadge>
      ) : null}
      {!person.donor && !person.admin && !person.packExemptAt ? (
        <span className="text-xs text-muted-foreground">{"\u2013"}</span>
      ) : null}
    </div>
  )
}

export function hasRole(person: Person): boolean {
  return person.donor || person.admin || Boolean(person.packExemptAt)
}

/** One dialog is open at a time, rendered beside the actions and never inside them. */
type PersonDialog = "grant" | "playtime" | "revoke" | "unlink" | "make-admin" | "revoke-admin" | "pack"

/**
 * Offers everything that can be done to one person, with its dialogs. A dialog is rendered beside the menu, because a Radix popover unmounts one opened from inside it.
 */
export function PersonActions({ person, menu = false }: { person: Person; menu?: boolean }) {
  const people = usePeople()
  const me = useMe()
  const [dialog, setDialog] = useState<PersonDialog | null>(null)
  const below = adminsBelow(people.data ?? [], me.data?.id)
  const close = (open: boolean) => (open ? null : setDialog(null))
  const actions = rowActions(person, {
    onGrant: () => setDialog("grant"),
    onPlaytime: () => setDialog("playtime"),
    onRevoke: () => setDialog("revoke"),
    onUnlink: () => setDialog("unlink"),
    onMakeAdmin: () => setDialog("make-admin"),
    onRevokeAdmin: below.has(person.discordId) ? () => setDialog("revoke-admin") : undefined,
    onPack: () => setDialog("pack"),
  })

  return (
    <>
      {menu ? (
        <RowActions menu label={t("steward.people.actions-for", { name: personName(person) })} actions={actions} />
      ) : (
        // The ghost buttons' own padding, taken back.
        <div className="-mx-2.5 flex flex-wrap items-center gap-1">
          {actions.map((action) => (
            <span key={action.key} className="contents">
              {action.node}
            </span>
          ))}
        </div>
      )}
      {dialog === "grant" ? <GrantDialog person={person} open onOpenChange={close} /> : null}
      {dialog === "playtime" ? <PlaytimeDialog person={person} open onOpenChange={close} /> : null}
      {dialog === "revoke" ? <RevokeDialog person={person} open onOpenChange={close} /> : null}
      {dialog === "unlink" ? <UnlinkDialog person={person} open onOpenChange={close} /> : null}
      {dialog === "make-admin" ? <MakeAdminDialog person={person} open onOpenChange={close} /> : null}
      {dialog === "pack" ? <PackExemptionDialog person={person} open onOpenChange={close} /> : null}
      {dialog === "revoke-admin" ? (
        <RevokeAdminDialog
          person={person}
          branch={adminsBelow(people.data ?? [], person.discordId).size}
          open
          onOpenChange={close}
        />
      ) : null}
    </>
  )
}

/** Returns the actions one row offers, each only where it applies. */
function rowActions(
  person: Person,
  on: {
    onGrant: () => void
    onPlaytime: () => void
    onRevoke: () => void
    onUnlink: () => void
    onMakeAdmin: () => void
    onRevokeAdmin?: () => void
    onPack: () => void
  },
): RowAction[] {
  const actions: RowAction[] = []

  actions.push({
    key: "grant",
    node: (
      <Button type="button" variant="ghost" size="sm" onClick={on.onGrant}>
        <TERM_ICON.grantAccess aria-hidden />
        {t("steward.people.grant")}
      </Button>
    ),
  })

  actions.push({
    key: "playtime",
    node: (
      <Button type="button" variant="ghost" size="sm" onClick={on.onPlaytime}>
        <TERM_ICON.playtime aria-hidden />
        {t("steward.people.playtime")}
      </Button>
    ),
  })

  if (person.accessActive) {
    actions.push({
      key: "revoke",
      node: (
        <Button type="button" variant="ghost" size="sm" className="text-destructive" onClick={on.onRevoke}>
          <TERM_ICON.revokeAccess aria-hidden />
          {t("steward.people.revoke")}
        </Button>
      ),
    })
  }

  if (person.minecraftUuid) {
    actions.push({
      key: "unlink",
      node: (
        <Button type="button" variant="ghost" size="sm" className="text-destructive" onClick={on.onUnlink}>
          <TERM_ICON.unlink aria-hidden />
          {t("steward.people.unlink")}
        </Button>
      ),
    })
  }

  if (!person.admin && person.memberState === "MEMBER") {
    actions.push({
      key: "make-admin",
      node: (
        <Button type="button" variant="ghost" size="sm" onClick={on.onMakeAdmin}>
          <TERM_ICON.admin aria-hidden />
          {t("steward.people.make-admin")}
        </Button>
      ),
    })
  }

  if (person.admin && on.onRevokeAdmin) {
    actions.push({
      key: "revoke-admin",
      node: (
        <Button type="button" variant="ghost" size="sm" className="text-destructive" onClick={on.onRevokeAdmin}>
          <TERM_ICON.revokeAdmin aria-hidden />
          {t("steward.people.revoke-admin")}
        </Button>
      ),
    })
  }

  // Last on purpose, because this is for whoever draws the pack and not a routine action.
  if (person.packExemptAt || person.minecraftUuid) {
    actions.push({
      key: "pack",
      node: (
        <Button type="button" variant="ghost" size="sm" onClick={on.onPack}>
          <TERM_ICON.pack aria-hidden />
          {t("steward.people.pack", { exempted: Boolean(person.packExemptAt) })}
        </Button>
      ),
    })
  }

  return actions
}

function packExemptText(person: Person, people: readonly Person[]): string {
  const admin = people.find((other) => other.discordId === person.packExemptBy)
  const by = admin ? personName(admin) : (person.packExemptBy ?? t("steward.people.some-admin"))
  return t("steward.people.no-pack-tip", { by, at: person.packExemptAt ?? "" })
}

function grantedByText(person: Person, people: readonly Person[]): string {
  if (!person.adminGrantedBy) return t("steward.people.root-admin")
  const granter = people.find((other) => other.discordId === person.adminGrantedBy)
  return t("steward.people.granted-by", { name: granter ? personName(granter) : person.adminGrantedBy })
}
