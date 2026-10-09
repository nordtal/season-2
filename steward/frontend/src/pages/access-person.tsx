import {
  CrownCrossIcon,
  CrownIcon,
  HourglassIcon,
  LinkBreakIcon,
  PackageIcon,
  ShieldSlashIcon,
  UserPlusIcon,
  WarningIcon,
} from "@phosphor-icons/react"
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

/** What the Users page and a person's page show and change about one person: the badges, the actions, every dialog. */

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
 * Granting: the bot writes a period with the source ADMIN (no payment, no tab), gives the role and tells the person.
 *
 * A day is 24 hours; a period is appended behind a running one and starts no earlier than the SMP launch. An unknown id makes an account.
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
        {Number.isFinite(parsedDays) && parsedDays > MOST_DAYS ? (
          <p className="text-sm text-destructive">{t("steward.people.most-days", { most: MOST_DAYS })}</p>
        ) : null}
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
            counted: person.playtimeSeconds ?? 0,
            becoming: seconds,
            usable,
          })}
        </p>
      </div>
    </AskThenAct>
  )
}

/** Unlinks the Minecraft account; the paid period stays, the person may link again, and the bot tells them. */
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

/** Lets one player through without the resource pack from their next login on, or takes that back. */
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

/** Makes a member an admin below the signed-in one, whom only they and the admins above can revoke; the Discord role follows. */
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
 * The proxy re-checks connected players, so revoking throws them out. A revoked period stays on record, marked.
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
            toast.success(personToast(t("steward.people.revoked-for", { count: result.revoked }), person.discordId))
          },
          onError: (error) => {
            toast.error(t("steward.people.not-revoked"), { description: String(error) })
          },
        })
      }}
    >
      <p className="flex items-start gap-2 rounded-md border border-warning/30 bg-warning/8 px-3 py-2 text-sm text-warning">
        <WarningIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
        {t("steward.people.thrown-out")}
      </p>
    </AskThenAct>
  )
}

/**
 * Access, from `accessActive` (the login decision) and `accessUntil` (the latest period on record).
 *
 * Inactive with a future period is revoked or not yet started; the tooltip names both.
 */
export function AccessBadge({ person, now }: { person: Person; now: number }) {
  const until = person.accessUntil ? new Date(person.accessUntil).getTime() : null

  if (person.accessActive) {
    return (
      <StatusBadge tone="ok" tipContent={t("steward.people.active-tip", { until: person.accessUntil ?? "" })}>
        {t("steward.people.active-until", { until: person.accessUntil ?? "" })}
      </StatusBadge>
    )
  }
  if (until === null) {
    return (
      <StatusBadge tone="idle" tipContent={t("steward.people.never-tip")}>
        {t("steward.people.never")}
      </StatusBadge>
    )
  }
  if (until > now) {
    return (
      <StatusBadge tone="down" tipContent={t("steward.people.no-access-tip", { until: person.accessUntil ?? "" })}>
        {t("steward.people.no-access")}
      </StatusBadge>
    )
  }
  return (
    <StatusBadge tone="idle" tipContent={t("steward.people.expired-tip")}>
      {t("steward.people.expired", { at: person.accessUntil ?? "" })}
    </StatusBadge>
  )
}

/** Warns about the one state with no face to draw: paid but no Minecraft account linked. */
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

/** The roles a person holds, as badges; a dash for none, which a phone's card leaves out with its line. */
export function RoleBadges({ person, people }: { person: Person; people: readonly Person[] }) {
  return (
    <div className="flex flex-wrap items-center gap-1">
      {person.donor ? (
        <StatusBadge tone="idle" tipContent={t("steward.people.supporter-tip")}>
          {t("steward.people.supporter")}
        </StatusBadge>
      ) : null}
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

/** Whether a person holds any role, for the line a phone's card draws only then. */
export function hasRole(person: Person): boolean {
  return person.donor || person.admin || Boolean(person.packExemptAt)
}

/** Which of a person's dialogs is open; one at a time, rendered beside the actions and never inside them. */
type PersonDialog = "grant" | "playtime" | "revoke" | "unlink" | "make-admin" | "revoke-admin" | "pack"

/**
 * Everything that can be done to one person, with its dialogs: a menu in a roster row, buttons on their page.
 *
 * A dialog is rendered beside the menu, since a Radix popover unmounts one opened from inside it.
 */
export function PersonActions({ person, menu = false }: { person: Person; menu?: boolean }) {
  const people = usePeople()
  const me = useMe()
  const [dialog, setDialog] = useState<PersonDialog | null>(null)
  // Which admins the signed-in one may revoke: their own branch, and nobody else's.
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
        // The ghost buttons' own padding, taken back, so the first label starts where the name above it does.
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

/**
 * The actions one row offers, each only where it applies.
 *
 * Revoke needs active access, Unlink a linked account; Grant is always there. The periods are on the page.
 */
function rowActions(
  person: Person,
  on: {
    onGrant: () => void
    onPlaytime: () => void
    onRevoke: () => void
    onUnlink: () => void
    onMakeAdmin: () => void
    /** Absent unless this admin is below the signed-in one. */
    onRevokeAdmin?: () => void
    onPack: () => void
  },
): RowAction[] {
  const actions: RowAction[] = []

  actions.push({
    key: "grant",
    node: (
      <Button type="button" variant="ghost" size="sm" onClick={on.onGrant}>
        <UserPlusIcon aria-hidden />
        {t("steward.people.grant")}
      </Button>
    ),
  })

  actions.push({
    key: "playtime",
    node: (
      <Button type="button" variant="ghost" size="sm" onClick={on.onPlaytime}>
        <HourglassIcon aria-hidden />
        {t("steward.people.playtime")}
      </Button>
    ),
  })

  if (person.accessActive) {
    actions.push({
      key: "revoke",
      node: (
        <Button type="button" variant="ghost" size="sm" className="text-destructive" onClick={on.onRevoke}>
          <ShieldSlashIcon aria-hidden />
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
          <LinkBreakIcon aria-hidden />
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
          <CrownIcon aria-hidden />
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
          <CrownCrossIcon aria-hidden />
          {t("steward.people.revoke-admin")}
        </Button>
      ),
    })
  }

  // Last on purpose: this is for whoever draws the pack, not a routine action.
  if (person.packExemptAt || person.minecraftUuid) {
    actions.push({
      key: "pack",
      node: (
        <Button type="button" variant="ghost" size="sm" onClick={on.onPack}>
          <PackageIcon aria-hidden />
          {t("steward.people.pack", { exempted: Boolean(person.packExemptAt) })}
        </Button>
      ),
    })
  }

  return actions
}

/** The exemption badge's tooltip: who let them through without the pack, and when. */
function packExemptText(person: Person, people: readonly Person[]): string {
  const admin = people.find((other) => other.discordId === person.packExemptBy)
  const by = admin ? personName(admin) : (person.packExemptBy ?? t("steward.people.some-admin"))
  return t("steward.people.no-pack-tip", { by, at: person.packExemptAt ?? "" })
}

/** The admin badge's tooltip: who granted this one, by the name the roster knows them by. */
function grantedByText(person: Person, people: readonly Person[]): string {
  if (!person.adminGrantedBy) return t("steward.people.root-admin")
  const granter = people.find((other) => other.discordId === person.adminGrantedBy)
  return t("steward.people.granted-by", { name: granter ? personName(granter) : person.adminGrantedBy })
}
