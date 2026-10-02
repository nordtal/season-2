import {
  ArrowSquareOutIcon,
  ClockCounterClockwiseIcon,
  CrownCrossIcon,
  CrownIcon,
  HandCoinsIcon,
  HourglassIcon,
  LinkBreakIcon,
  PackageIcon,
  MagnifyingGlassIcon,
  ShieldSlashIcon,
  UserPlusIcon,
  WarningCircleIcon,
  WarningIcon,
} from "@phosphor-icons/react"
import type { ReactNode } from "react"
import { useState } from "react"
import { toast } from "sonner"

import { adminsBelow } from "@/lib/admin-tree"
import type { Grant, JournalEntry, Payment, Person } from "@/lib/api"
import { count, date, dateTime, euros, playtime, relative, splitPlaytime } from "@/lib/format"
import { useNow } from "@/lib/use-now"
import {
  useGrantAccess,
  useEnforcePack,
  useExemptFromPack,
  useGrantAdmin,
  useGrants,
  useJournal,
  usePayments,
  useMe,
  usePeople,
  useRevokeAccess,
  useRevokeAdmin,
  useSetPlaytime,
  useSettle,
  useUnlink,
} from "@/lib/queries"
import { Actor, Entity } from "@/components/steward/entity"
import { PageHeader } from "@/components/steward/page-header"
import { RowActions, type RowAction } from "@/components/steward/row-actions"
import { Stat } from "@/components/steward/stat"
import { StatusBadge, type Tone } from "@/components/steward/status"
import { Empty, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import {
  ResponsiveAlertDialog,
  ResponsiveAlertDialogAction,
  ResponsiveAlertDialogCancel,
  ResponsiveAlertDialogContent,
  ResponsiveAlertDialogDescription,
  ResponsiveAlertDialogFooter,
  ResponsiveAlertDialogHeader,
  ResponsiveAlertDialogTitle,
  ResponsiveAlertDialogTrigger,
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogDescription,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Separator } from "@/components/ui/separator"
import { Switch } from "@/components/ui/switch"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"

/**
 * The access pages: who may join, what they paid, and what an admin did about it.
 *
 * One file, since Payments, Users and Journal share one vocabulary for the chain request, tab, paid, access.
 */

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

function MemberBadge({ state }: { state: string }) {
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

/**
 * Access, from `accessActive` (the login decision) and `accessUntil` (the latest period on record).
 *
 * Inactive with a future period is revoked or not yet started; the tooltip names both.
 */
function AccessBadge({ person, now }: { person: Person; now: number }) {
  const until = person.accessUntil ? new Date(person.accessUntil).getTime() : null

  if (person.accessActive) {
    return (
      <StatusBadge
        tone="ok"
        tipContent={`An unrevoked period covers right now, until ${dateTime(person.accessUntil)}.`}
      >
        active until {date(person.accessUntil)}
      </StatusBadge>
    )
  }
  if (until === null) {
    return (
      <StatusBadge tone="idle" tipContent="No period has ever been written for this account.">
        never
      </StatusBadge>
    )
  }
  if (until > now) {
    return (
      <StatusBadge
        tone="down"
        tipContent={
          "No valid period covers now, although the latest one runs on paper until " +
          dateTime(person.accessUntil) +
          ". That means either revoked - or bought before the SMP opened, and therefore not yet begun. Which of the two is shown in this person's periods."
        }
      >
        no access
      </StatusBadge>
    )
  }
  return (
    <StatusBadge tone="idle" tipContent="The latest period has expired.">
      expired {relative(person.accessUntil, now)}
    </StatusBadge>
  )
}

/** Warns about the one state with no face to draw: paid but no Minecraft account linked. */
function LinkBadge({ person }: { person: Person }) {
  return (
    <StatusBadge
      tone={person.accessActive ? "warn" : "idle"}
      tipContent={
        person.accessActive
          ? "Access paid for, but no Minecraft account linked - this person cannot reach the server until they type the code from the login screen into Discord."
          : "No Minecraft account linked."
      }
    >
      not linked
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

/** Rows per page of the People table, paged after filtering. */
const PEOPLE_PAGE_SIZE = 20

/** The roster's header row and column widths, shared by the real rows and the waiting ones. */
function PeopleTable({ children }: { children: ReactNode }) {
  return (
    <Table className="steward-table">
      <TableHeader>
        <TableRow>
          <TableHead className="w-[16rem]">Person</TableHead>
          <TableHead className="w-[20rem]">
            <AccessColumnHead />
          </TableHead>
          <TableHead className="w-[9rem]">Minecraft</TableHead>
          <TableHead className="w-[9rem]">Roles</TableHead>
          {/* The prestige tier is derived from this number, so it is the one lever an admin has. */}
          <TableHead className="w-[7rem]">Playtime</TableHead>
          <TableHead className="w-[15rem]" />
        </TableRow>
      </TableHeader>
      <TableBody>{children}</TableBody>
    </Table>
  )
}

/** How many rows are drawn before the roster is there. */
const WAITING_PEOPLE = [0, 1, 2, 3, 4, 5, 6, 7]

/** One person before there is one, in the shapes of the real row; the row action stays empty. */
function WaitingPersonRow() {
  return (
    <TableRow>
      {/* Drawn at the declared widths, so the headings do not slide when the roster lands. */}
      <TableCell data-label="Person" className="font-medium">
        <div className="flex items-center gap-2">
          <Skeleton className="size-6 shrink-0 rounded-full" />
          <SkeletonText width="full" className="max-w-[13rem]" />
        </div>
      </TableCell>
      <TableCell data-label="Access">
        <Skeleton className="h-5 w-[15rem] max-w-full rounded-full" />
      </TableCell>
      <TableCell data-label="Minecraft">
        <div className="flex items-center gap-2">
          <Skeleton className="size-5 shrink-0 rounded-sm" />
          <SkeletonText width="full" className="max-w-[6.5rem]" />
        </div>
      </TableCell>
      <TableCell data-label="Roles">
        <Skeleton className="h-5 w-[7rem] max-w-full rounded-full" />
      </TableCell>
      <TableCell data-label="Playtime">
        <SkeletonText width="medium" className="min-w-[3rem]" />
      </TableCell>
      <TableCell />
    </TableRow>
  )
}

/**
 * The roster: everyone the bot knows, and what they may.
 *
 * Every write here is a request the bot carries out, as `/access` in Discord does.
 */
export function AccessPage() {
  const people = usePeople()
  const me = useMe()
  const [needle, setNeedle] = useState("")
  const [onlyWithAccess, setOnlyWithAccess] = useState(false)
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<Person | null>(null)
  /** The person being revoked; separate from `selected`, so there is never a dialog inside a dialog. */
  const [revoking, setRevoking] = useState<Person | null>(null)
  /** Every dialog is rendered beside the table, since a Radix popover unmounts one opened from inside it. */
  const [unlinking, setUnlinking] = useState<Person | null>(null)
  const [granting, setGranting] = useState<Person | null>(null)
  // Not `playtime`: that name is the formatter this page draws the column with.
  const [playtimeFor, setPlaytimeFor] = useState<Person | null>(null)
  const [makingAdmin, setMakingAdmin] = useState<Person | null>(null)
  const [unmakingAdmin, setUnmakingAdmin] = useState<Person | null>(null)
  const [packFor, setPackFor] = useState<Person | null>(null)
  // Which admins the signed-in one may revoke: their own branch, and nobody else's.
  const below = adminsBelow(people.data ?? [], me.data?.id)
  // One clock for the whole render, so that two badges in one row cannot disagree about "now".
  const now = useNow()

  /** Resets the page whenever the filter changes, so a stale page number never flashes. */
  function changeNeedle(value: string) {
    setNeedle(value)
    setPage(0)
  }
  function changeOnlyWithAccess(value: boolean) {
    setOnlyWithAccess(value)
    setPage(0)
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="Users" actions={<GrantDialog />} />

      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium">People</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {/* Stacked below `sm`, one row above, with `min-w-0` on the input so it can shrink. */}
          <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-center sm:gap-4">
            <div className="flex w-full min-w-0 items-center gap-2 sm:min-w-64 sm:flex-1">
              <MagnifyingGlassIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
              <Input
                value={needle}
                onChange={(event) => changeNeedle(event.target.value)}
                /** Short enough for 390px; the long sentence is the accessible name. */
                placeholder="Filter by name or id"
                aria-label="Filter people by name, Discord id, or Minecraft account"
                className="min-w-0"
                autoComplete="off"
              />
            </div>
            <div className="flex min-w-0 items-center gap-2">
              <Switch id="only-with-access" checked={onlyWithAccess} onCheckedChange={changeOnlyWithAccess} />
              <Label htmlFor="only-with-access">with access only</Label>
            </div>
          </div>

          <QueryState
            query={people}
            empty={{
              title: "Nobody yet",
              note: "The bot has not seen a single Discord account yet - or it is not running.",
            }}
            isEmpty={(list: Person[]) => list.length === 0}
          >
            {(list) => {
              if (list === undefined) {
                /** Everything above the rows is already on screen, so only eight rows wait. */
                return (
                  <>
                    <PeopleTable>
                      {WAITING_PEOPLE.map((index) => (
                        <WaitingPersonRow key={index} />
                      ))}
                    </PeopleTable>
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <SkeletonText className="w-44 text-xs" />
                    </div>
                  </>
                )
              }
              const trimmed = needle.trim().toLowerCase()
              /**
               * Matches the Discord name, the Minecraft name and both ids, so an id from a log still finds the person.
               */
              const rows = list.filter(
                (person) =>
                  (!onlyWithAccess || person.accessActive) &&
                  (trimmed === "" ||
                    person.discordId.toLowerCase().includes(trimmed) ||
                    (person.discordUsername ?? "").toLowerCase().includes(trimmed) ||
                    (person.discordDisplayName ?? "").toLowerCase().includes(trimmed) ||
                    (person.mcName ?? "").toLowerCase().includes(trimmed) ||
                    (person.minecraftUuid ?? "").toLowerCase().includes(trimmed)),
              )
              if (rows.length === 0) {
                return (
                  <Empty
                    title="Nobody matches"
                    note={
                      onlyWithAccess
                        ? 'With this filter and "with access only" nobody is left.'
                        : "No loaded account contains this string in its name or either id."
                    }
                  />
                )
              }
              /** Paged after filtering, so a search reaches the whole roster. */
              const pageCount = Math.max(1, Math.ceil(rows.length / PEOPLE_PAGE_SIZE))
              const clampedPage = Math.min(page, pageCount - 1)
              const paged = rows.slice(
                clampedPage * PEOPLE_PAGE_SIZE,
                clampedPage * PEOPLE_PAGE_SIZE + PEOPLE_PAGE_SIZE,
              )
              return (
                <>
                  <PeopleTable>
                    {paged.map((person) => (
                      <TableRow key={person.discordId}>
                        <TableCell data-label="Person" className="font-medium">
                          <div className="flex flex-wrap items-center gap-2">
                            <Entity id={person.discordId} kind="discord" />
                            {/* "Member" is left unsaid; LEFT and BANNED get a badge beside the name. */}
                            {person.memberState !== "MEMBER" ? <MemberBadge state={person.memberState} /> : null}
                          </div>
                        </TableCell>
                        <TableCell data-label="Access">
                          <AccessBadge person={person} now={now} />
                        </TableCell>
                        <TableCell data-label="Minecraft">
                          {person.minecraftUuid ? (
                            <Entity id={person.minecraftUuid} kind="minecraft" />
                          ) : (
                            <LinkBadge person={person} />
                          )}
                        </TableCell>
                        <TableCell data-label="Roles">
                          <div className="flex items-center gap-1">
                            {person.donor ? (
                              <StatusBadge
                                tone="idle"
                                tipContent="Given once, never taken away - which is why handing the role out in Discord is harmless."
                              >
                                Supporter
                              </StatusBadge>
                            ) : null}
                            {person.admin ? (
                              <StatusBadge tone="idle" tipContent={grantedByText(person, list)}>
                                Admin
                              </StatusBadge>
                            ) : null}
                            {person.packExemptAt ? (
                              <StatusBadge tone="warn" tipContent={packExemptText(person, list)}>
                                No resource pack
                              </StatusBadge>
                            ) : null}
                            {!person.donor && !person.admin && !person.packExemptAt ? (
                              <span className="text-xs text-muted-foreground">{"\u2013"}</span>
                            ) : null}
                          </div>
                        </TableCell>
                        <TableCell data-label="Playtime">
                          {/* `playtime` draws the dash for somebody who has never been online. */}
                          <span className="text-sm tabular-nums">{playtime(person.playtimeSeconds ?? undefined)}</span>
                        </TableCell>
                        <TableCell>
                          <RowActions
                            label={`Actions for ${personName(person)}`}
                            actions={rowActions(person, {
                              onPeriods: () => setSelected(person),
                              onGrant: () => setGranting(person),
                              onPlaytime: () => setPlaytimeFor(person),
                              onRevoke: () => setRevoking(person),
                              onUnlink: () => setUnlinking(person),
                              onMakeAdmin: () => setMakingAdmin(person),
                              onRevokeAdmin: below.has(person.discordId) ? () => setUnmakingAdmin(person) : undefined,
                              onPack: () => setPackFor(person),
                            })}
                          />
                        </TableCell>
                      </TableRow>
                    ))}
                  </PeopleTable>
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <p className="text-xs text-muted-foreground">
                      {count(rows.length)} of {count(list.length)} loaded accounts.
                    </p>
                    {pageCount > 1 ? (
                      <div className="flex items-center gap-2">
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          disabled={clampedPage === 0}
                          onClick={() => setPage((current) => Math.max(0, current - 1))}
                        >
                          Previous
                        </Button>
                        <span className="text-xs text-muted-foreground tnum">
                          Page {clampedPage + 1} of {pageCount}
                        </span>
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          disabled={clampedPage >= pageCount - 1}
                          onClick={() => setPage((current) => current + 1)}
                        >
                          Next
                        </Button>
                      </div>
                    ) : null}
                  </div>
                </>
              )
            }}
          </QueryState>
        </CardContent>
      </Card>

      <ResponsiveDialog open={selected !== null} onOpenChange={(open) => (open ? null : setSelected(null))}>
        <ResponsiveDialogContent className="sm:max-w-2xl">
          {selected ? (
            <PersonGrants
              person={selected}
              now={now}
              onRevoke={() => {
                const person = selected
                setSelected(null)
                setRevoking(person)
              }}
            />
          ) : null}
        </ResponsiveDialogContent>
      </ResponsiveDialog>

      {revoking ? (
        <RevokeDialog person={revoking} open onOpenChange={(open) => (open ? null : setRevoking(null))} />
      ) : null}

      {/* Rendered here rather than in the row, for the reason `unlinking` is declared with. */}
      {unlinking ? (
        <UnlinkDialog person={unlinking} open onOpenChange={(open) => (open ? null : setUnlinking(null))} />
      ) : null}

      {makingAdmin ? (
        <MakeAdminDialog person={makingAdmin} open onOpenChange={(open) => (open ? null : setMakingAdmin(null))} />
      ) : null}

      {packFor ? (
        <PackExemptionDialog person={packFor} open onOpenChange={(open) => (open ? null : setPackFor(null))} />
      ) : null}

      {unmakingAdmin ? (
        <RevokeAdminDialog
          person={unmakingAdmin}
          branch={adminsBelow(people.data ?? [], unmakingAdmin.discordId).size}
          open
          onOpenChange={(open) => (open ? null : setUnmakingAdmin(null))}
        />
      ) : null}

      {granting ? (
        <GrantDialog person={granting} open onOpenChange={(open) => (open ? null : setGranting(null))} />
      ) : null}

      {playtimeFor ? (
        <PlaytimeDialog person={playtimeFor} open onOpenChange={(open) => (open ? null : setPlaytimeFor(null))} />
      ) : null}
    </div>
  )
}

/** What to call somebody in a control's accessible name, in the order the table itself reads. */
function personName(person: Person): string {
  return person.discordDisplayName ?? person.discordUsername ?? person.mcName ?? person.discordId
}

/**
 * The actions one row offers, each only where it applies.
 *
 * Periods need a period, Revoke active access, Unlink a linked account; Grant is always there.
 */
function rowActions(
  person: Person,
  on: {
    onPeriods: () => void
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

  if (person.accessUntil) {
    actions.push({
      key: "periods",
      node: (
        <Button type="button" variant="ghost" size="sm" onClick={on.onPeriods}>
          <ClockCounterClockwiseIcon aria-hidden />
          Periods
        </Button>
      ),
    })
  }

  actions.push({
    key: "grant",
    node: (
      <Button type="button" variant="ghost" size="sm" onClick={on.onGrant}>
        <UserPlusIcon aria-hidden />
        Grant
      </Button>
    ),
  })

  actions.push({
    key: "playtime",
    node: (
      <Button type="button" variant="ghost" size="sm" onClick={on.onPlaytime}>
        <HourglassIcon aria-hidden />
        Playtime
      </Button>
    ),
  })

  if (person.accessActive) {
    actions.push({
      key: "revoke",
      node: (
        <Button type="button" variant="ghost" size="sm" className="text-destructive" onClick={on.onRevoke}>
          <ShieldSlashIcon aria-hidden />
          Revoke
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
          Unlink
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
          Make admin
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
          Revoke admin
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
          {person.packExemptAt ? "Enforce resource pack" : "Skip resource pack"}
        </Button>
      ),
    })
  }

  return actions
}

/** The exemption badge's tooltip: who let them through without the pack, and when. */
function packExemptText(person: Person, people: readonly Person[]): string {
  const admin = people.find((other) => other.discordId === person.packExemptBy)
  const by = admin ? personName(admin) : (person.packExemptBy ?? "an admin")
  const at = person.packExemptAt ? ` on ${dateTime(person.packExemptAt)}` : ""
  return `Plays without the resource pack from their next login on. Set by ${by}${at}.`
}

/** The admin badge's tooltip: who granted this one, by the name the roster knows them by. */
function grantedByText(person: Person, people: readonly Person[]): string {
  if (!person.adminGrantedBy) return "Root admin"
  const granter = people.find((other) => other.discordId === person.adminGrantedBy)
  return `Granted by ${granter ? personName(granter) : person.adminGrantedBy}`
}

/** The header of the access column, with the reason its two halves disagree. */
function AccessColumnHead() {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span tabIndex={0} className="underline decoration-dotted underline-offset-4">
          Access
        </span>
      </TooltipTrigger>
      <TooltipContent className="max-w-xs">
        Two readings, deliberately not one: whether an unrevoked period covers right now - and when the latest period
        ends, revoked ones included. Without the second, somebody whose access was taken away would look exactly like
        somebody who never had any.
      </TooltipContent>
    </Tooltip>
  )
}

/**
 * Granting, with the arithmetic named out loud.
 *
 * A new period is appended behind a running one; one bought before the SMP opens starts then.
 */
function GrantDialog({
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
    <ResponsiveAlertDialog open={open} onOpenChange={onOpenChange}>
      {open === undefined ? (
        <ResponsiveAlertDialogTrigger asChild>
          <Button type="button">
            <UserPlusIcon aria-hidden />
            Grant access
          </Button>
        </ResponsiveAlertDialogTrigger>
      ) : null}
      <ResponsiveAlertDialogContent>
        <ResponsiveAlertDialogHeader>
          <ResponsiveAlertDialogTitle>Grant access by hand</ResponsiveAlertDialogTitle>
          <ResponsiveAlertDialogDescription>
            The bot writes a period with the source <code className="text-xs">ADMIN</code> - no payment, no bunq tab -
            gives the role and tells the person by direct message.
          </ResponsiveAlertDialogDescription>
        </ResponsiveAlertDialogHeader>

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

        <ResponsiveAlertDialogFooter>
          <ResponsiveAlertDialogCancel>Cancel</ResponsiveAlertDialogCancel>
          <ResponsiveAlertDialogAction
            disabled={!usable || grant.isPending}
            onClick={() => {
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
            Grant
          </ResponsiveAlertDialogAction>
        </ResponsiveAlertDialogFooter>
      </ResponsiveAlertDialogContent>
    </ResponsiveAlertDialog>
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

function PlaytimeDialog({
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
    <ResponsiveAlertDialog open={open} onOpenChange={onOpenChange}>
      <ResponsiveAlertDialogContent>
        <ResponsiveAlertDialogHeader>
          <ResponsiveAlertDialogTitle>Set play time</ResponsiveAlertDialogTitle>
          <ResponsiveAlertDialogDescription>
            Replaces the counted total for {personName(person)}. The prestige tier follows from it, and there is nothing
            else to set.
          </ResponsiveAlertDialogDescription>
        </ResponsiveAlertDialogHeader>

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

        <ResponsiveAlertDialogFooter>
          <ResponsiveAlertDialogCancel>Cancel</ResponsiveAlertDialogCancel>
          <ResponsiveAlertDialogAction
            disabled={!usable || write.isPending}
            onClick={() => {
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
            Save
          </ResponsiveAlertDialogAction>
        </ResponsiveAlertDialogFooter>
      </ResponsiveAlertDialogContent>
    </ResponsiveAlertDialog>
  )
}

/** Unlinks the Minecraft account; the Discord account keeps its access, and the bot tells the person. */
function UnlinkDialog({
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
    <ResponsiveAlertDialog open={open} onOpenChange={onOpenChange}>
      <ResponsiveAlertDialogContent>
        <ResponsiveAlertDialogHeader>
          <ResponsiveAlertDialogTitle>Unlink?</ResponsiveAlertDialogTitle>
          <ResponsiveAlertDialogDescription>
            Breaks the link between this Discord account and its Minecraft account. The paid period is untouched; the
            person can link a Minecraft account again afterwards.
          </ResponsiveAlertDialogDescription>
        </ResponsiveAlertDialogHeader>
        <ResponsiveAlertDialogFooter>
          <ResponsiveAlertDialogCancel disabled={unlink.isPending}>Cancel</ResponsiveAlertDialogCancel>
          <ResponsiveAlertDialogAction
            variant="destructive"
            disabled={unlink.isPending}
            onClick={() => {
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
          >
            Unlink
          </ResponsiveAlertDialogAction>
        </ResponsiveAlertDialogFooter>
      </ResponsiveAlertDialogContent>
    </ResponsiveAlertDialog>
  )
}

/** Lets one player through without the resource pack, or takes that back. */
function PackExemptionDialog({
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
    <ResponsiveAlertDialog open={open} onOpenChange={onOpenChange}>
      <ResponsiveAlertDialogContent>
        <ResponsiveAlertDialogHeader>
          <ResponsiveAlertDialogTitle>
            {exempted
              ? `Require the resource pack for ${personName(person)} again?`
              : `Let ${personName(person)} play without the resource pack?`}
          </ResponsiveAlertDialogTitle>
          <ResponsiveAlertDialogDescription>
            {exempted
              ? "From their next login on, they get the pack like everybody else."
              : "From their next login on, the network sends them no pack, until an admin requires it again. Their own local pack then shows."}
          </ResponsiveAlertDialogDescription>
        </ResponsiveAlertDialogHeader>
        <ResponsiveAlertDialogFooter>
          <ResponsiveAlertDialogCancel disabled={change.isPending}>Cancel</ResponsiveAlertDialogCancel>
          <ResponsiveAlertDialogAction
            disabled={change.isPending}
            onClick={() => {
              change.mutate(person.discordId, {
                onSuccess: () => {
                  toast.success(
                    personToast(exempted ? "Resource pack required" : "Resource pack skipped", person.discordId),
                  )
                },
                onError: (error) => {
                  toast.error("Nothing was changed", { description: String(error) })
                },
              })
            }}
          >
            {exempted ? "Enforce resource pack" : "Skip resource pack"}
          </ResponsiveAlertDialogAction>
        </ResponsiveAlertDialogFooter>
      </ResponsiveAlertDialogContent>
    </ResponsiveAlertDialog>
  )
}

/** Makes a member an admin below the signed-in one. The Discord admin role follows. */
function MakeAdminDialog({
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
    <ResponsiveAlertDialog open={open} onOpenChange={onOpenChange}>
      <ResponsiveAlertDialogContent>
        <ResponsiveAlertDialogHeader>
          <ResponsiveAlertDialogTitle>Make {personName(person)} an admin?</ResponsiveAlertDialogTitle>
          <ResponsiveAlertDialogDescription>
            Below you. Only you and the admins above you can revoke it.
          </ResponsiveAlertDialogDescription>
        </ResponsiveAlertDialogHeader>
        <ResponsiveAlertDialogFooter>
          <ResponsiveAlertDialogCancel disabled={grant.isPending}>Cancel</ResponsiveAlertDialogCancel>
          <ResponsiveAlertDialogAction
            disabled={grant.isPending}
            onClick={() => {
              grant.mutate(person.discordId, {
                onSuccess: () => {
                  toast.success(personToast("Admin", person.discordId))
                },
                onError: (error) => {
                  toast.error("Nobody was made an admin", { description: String(error) })
                },
              })
            }}
          >
            Make admin
          </ResponsiveAlertDialogAction>
        </ResponsiveAlertDialogFooter>
      </ResponsiveAlertDialogContent>
    </ResponsiveAlertDialog>
  )
}

/** Takes admin from somebody below the signed-in one, and from their whole branch. */
function RevokeAdminDialog({
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
    <ResponsiveAlertDialog open={open} onOpenChange={onOpenChange}>
      <ResponsiveAlertDialogContent>
        <ResponsiveAlertDialogHeader>
          <ResponsiveAlertDialogTitle>Revoke admin from {personName(person)}?</ResponsiveAlertDialogTitle>
          <ResponsiveAlertDialogDescription>
            {branch === 0
              ? "Their open sessions end."
              : `${count(branch)} ${branch === 1 ? "admin" : "admins"} below them lose it too. Every open session of theirs ends.`}
          </ResponsiveAlertDialogDescription>
        </ResponsiveAlertDialogHeader>
        <ResponsiveAlertDialogFooter>
          <ResponsiveAlertDialogCancel disabled={revoke.isPending}>Cancel</ResponsiveAlertDialogCancel>
          <ResponsiveAlertDialogAction
            variant="destructive"
            disabled={revoke.isPending}
            onClick={() => {
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
          >
            Revoke admin
          </ResponsiveAlertDialogAction>
        </ResponsiveAlertDialogFooter>
      </ResponsiveAlertDialogContent>
    </ResponsiveAlertDialog>
  )
}

/** Settles the one OPEN request of this row by hand; the bot books it as if bunq had reported it. */
function SettleAction({ reference }: { reference: string }) {
  const settle = useSettle()
  return (
    <ResponsiveAlertDialog>
      <ResponsiveAlertDialogTrigger asChild>
        <Button type="button" variant="outline" size="sm" disabled={settle.isPending}>
          <HandCoinsIcon aria-hidden />
          Settle
        </Button>
      </ResponsiveAlertDialogTrigger>
      <ResponsiveAlertDialogContent>
        <ResponsiveAlertDialogHeader>
          <ResponsiveAlertDialogTitle>Settle?</ResponsiveAlertDialogTitle>
          <ResponsiveAlertDialogDescription>
            Marks {reference} paid by hand and writes the access period it bought. Use this only once the money has
            actually arrived - it books access, it does not check bunq.
          </ResponsiveAlertDialogDescription>
        </ResponsiveAlertDialogHeader>
        <ResponsiveAlertDialogFooter>
          <ResponsiveAlertDialogCancel>Cancel</ResponsiveAlertDialogCancel>
          <ResponsiveAlertDialogAction
            onClick={() => {
              settle.mutate(reference, {
                onSuccess: (result) => {
                  if (result.outcome === "BOOKED") {
                    toast.success(`${reference} settled`, {
                      description: `${count(result.days)} days, valid until ${dateTime(
                        result.until ?? "",
                      )}. A journal line names you.`,
                    })
                  } else if (result.outcome === "NOT_OPEN") {
                    toast.warning(`${reference} was not open any more`, {
                      description: `It is ${result.was ?? "settled"} now. Nothing was booked.`,
                    })
                  } else {
                    toast.warning(`There is no payment ${reference}`, {
                      description: "Nothing was booked.",
                    })
                  }
                },
                onError: (error) => {
                  toast.error("Nothing was settled", { description: String(error) })
                },
              })
            }}
          >
            Settle
          </ResponsiveAlertDialogAction>
        </ResponsiveAlertDialogFooter>
      </ResponsiveAlertDialogContent>
    </ResponsiveAlertDialog>
  )
}

/**
 * Revokes every running period; the person dialog closes first, so there are never two focus traps.
 *
 * @param open when given, the dialog is controlled from outside and draws no trigger of its own
 */
function RevokeDialog({
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
    <ResponsiveAlertDialog open={open} onOpenChange={onOpenChange}>
      {open === undefined ? (
        <ResponsiveAlertDialogTrigger asChild>
          <Button type="button" variant="ghost" size="sm" className="text-destructive">
            <ShieldSlashIcon aria-hidden />
            Revoke
          </Button>
        </ResponsiveAlertDialogTrigger>
      ) : null}
      <ResponsiveAlertDialogContent>
        <ResponsiveAlertDialogHeader>
          <ResponsiveAlertDialogTitle>Revoke access?</ResponsiveAlertDialogTitle>
          <ResponsiveAlertDialogDescription>
            What is revoked is the <span className="text-foreground">whole remaining run</span> of the person this row
            names - every period not yet expired at once, not a single one.
          </ResponsiveAlertDialogDescription>
        </ResponsiveAlertDialogHeader>

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
            The entry stays and is only marked revoked - which is why a date still stands beside "no access" in the
            list, instead of the person looking like a stranger.
          </p>
        </div>

        <ResponsiveAlertDialogFooter>
          <ResponsiveAlertDialogCancel>Cancel</ResponsiveAlertDialogCancel>
          <ResponsiveAlertDialogAction
            variant="destructive"
            disabled={revoke.isPending}
            onClick={() => {
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
            Revoke
          </ResponsiveAlertDialogAction>
        </ResponsiveAlertDialogFooter>
      </ResponsiveAlertDialogContent>
    </ResponsiveAlertDialog>
  )
}

/** One person's chain, period by period; the writes live in the table row. */
function PersonGrants({ person, now, onRevoke }: { person: Person; now: number; onRevoke: () => void }) {
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

/** Eight rows of nothing while the requests are read. */
const WAITING_PAYMENTS = [0, 1, 2, 3, 4, 5, 6, 7]

const PAYMENT_STATES: Record<string, { label: string; tone: Tone; title: string }> = {
  OPEN: {
    label: "open",
    tone: "idle",
    title: "The tab is up, nothing has been paid yet. Only one request per person can be open.",
  },
  PAID: { label: "paid", tone: "ok", title: "The money has arrived and the period stands." },
  EXPIRED: {
    label: "lapsed",
    tone: "idle",
    title: "The deadline passed without payment.",
  },
  CANCELLED: { label: "cancelled", tone: "idle", title: "Cancelled before anything was paid." },
  SUPERSEDED: {
    label: "superseded",
    tone: "idle",
    title: "The same person started a new request, which closed this one.",
  },
}

/** OPEN and past its `expires`; the sweep has just not run yet. */
function isOverdue(payment: Payment, now: number): boolean {
  return payment.status === "OPEN" && new Date(payment.expires).getTime() <= now
}

/**
 * The payments: the first three links of the chain.
 *
 * The sum is what the tabs asked for, since the payer can edit the amount on bunq.me.
 */
export function PaymentsPage() {
  const payments = usePayments()
  const [status, setStatus] = useState("")
  const now = useNow()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="Payments" />

      <QueryState
        query={payments}
        empty={{
          title: "No payment request",
          note: "Nobody has requested access yet - or the bot is not running.",
        }}
        isEmpty={(list: Payment[]) => list.length === 0}
      >
        {(list) => {
          /** A sum over nothing is 0, which is not a waiting state, so every figure waits on `waiting`. */
          const waiting = list === undefined
          const rows = list ?? []
          const open = rows.filter((payment) => payment.status === "OPEN")
          const overdue = open.filter((payment) => isOverdue(payment, now))
          const paid = rows.filter((payment) => payment.status === "PAID")
          const requested = paid.reduce((sum, payment) => sum + payment.amountCents + payment.donationCents, 0)
          /** Built from the rows present, so a status added later still appears. */
          const present = [...new Set(rows.map((payment) => payment.status))].toSorted()
          const shown = rows.filter((payment) => status === "" || payment.status === status)

          return (
            <>
              <Card>
                <CardContent className="flex flex-wrap items-start gap-8 pt-6">
                  <Stat
                    label="Open"
                    value={waiting ? undefined : count(open.length)}
                    hint={waiting ? undefined : `${count(overdue.length)} of them past the deadline`}
                    tone={overdue.length > 0 ? "warn" : undefined}
                  />
                  <Stat label="Paid" value={waiting ? undefined : count(paid.length)} />
                  <Separator orientation="vertical" className="h-14" />
                  <Stat
                    label="Requested (paid requests)"
                    value={waiting ? undefined : euros(requested)}
                    hint="Amount plus donation, as the tab requested it"
                  />
                  <p className="max-w-prose text-xs text-muted-foreground">
                    This is <span className="text-foreground">not the balance</span>: on the bunq.me page the paying
                    person can change the amount, and what actually arrived is in none of these columns. This interface
                    does not ask bunq - the bot does.
                  </p>
                </CardContent>
              </Card>

              <Card>
                <CardHeader>
                  <CardTitle className="text-sm font-medium">Requests</CardTitle>
                </CardHeader>
                <CardContent className="flex flex-col gap-4">
                  <div className="flex flex-wrap items-center gap-2">
                    <Label htmlFor="payment-status" className="text-muted-foreground">
                      Status
                    </Label>
                    <Select
                      value={status === "" ? "ALL" : status}
                      onValueChange={(value) => setStatus(value === "ALL" ? "" : value)}
                    >
                      <SelectTrigger id="payment-status" className="w-full sm:w-56">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="ALL">all</SelectItem>
                        {present.map((value) => (
                          <SelectItem key={value} value={value}>
                            {PAYMENT_STATES[value]?.label ?? value}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    {overdue.length > 0 ? (
                      <span className="flex items-center gap-2 text-xs text-warning">
                        <WarningCircleIcon className="size-4 shrink-0" aria-hidden />
                        {count(overdue.length)} open request(s) are past their deadline - nobody is going to pay those,
                        they are only waiting for the bot's cleanup run.
                      </span>
                    ) : null}
                  </div>

                  {!waiting && shown.length === 0 ? (
                    <Empty
                      title="No request with this status"
                      note="None of the loaded requests carries this status."
                    />
                  ) : (
                    <Table className="steward-table">
                      <TableHeader>
                        <TableRow>
                          {/* `Created` sits in the `Reference` title and `Donation` under `Amount`. */}
                          <TableHead className="w-[7rem]">Reference</TableHead>
                          <TableHead className="w-[11rem]">Person</TableHead>
                          <TableHead className="w-[4rem] text-right">Days</TableHead>
                          <TableHead className="w-[7rem] text-right">Amount</TableHead>
                          <TableHead className="w-[9rem]">Status</TableHead>
                          <TableHead className="w-[11rem]">Deadline</TableHead>
                          <TableHead className="w-[11rem]">Paid</TableHead>
                          {/* `Tab` and `Settle`, which may stack. */}
                          <TableHead className="w-[11rem]" />
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {waiting
                          ? WAITING_PAYMENTS.map((index) => (
                              <TableRow key={index}>
                                <TableCell data-label="Reference">
                                  <SkeletonText width="medium" />
                                </TableCell>
                                <TableCell data-label="Person">
                                  <div className="flex items-center gap-2">
                                    <Skeleton className="size-6 shrink-0 rounded-full" />
                                    <SkeletonText width="long" className="max-w-[6rem]" />
                                  </div>
                                </TableCell>
                                <TableCell data-label="Days" className="text-right">
                                  <SkeletonText width="short" className="ml-auto" />
                                </TableCell>
                                <TableCell data-label="Amount" className="text-right">
                                  <SkeletonText width="medium" className="ml-auto" />
                                </TableCell>
                                <TableCell data-label="Status">
                                  <Skeleton className="h-5 w-20 rounded-full" />
                                </TableCell>
                                <TableCell data-label="Deadline">
                                  <SkeletonText width="long" />
                                </TableCell>
                                <TableCell data-label="Paid">
                                  <SkeletonText width="long" />
                                </TableCell>
                                <TableCell />
                              </TableRow>
                            ))
                          : shown.map((payment) => {
                              const state = PAYMENT_STATES[payment.status]
                              const late = isOverdue(payment, now)
                              return (
                                <TableRow key={payment.id}>
                                  <TableCell
                                    data-label="Reference"
                                    className="font-mono font-medium"
                                    title={`Created ${dateTime(payment.created)}`}
                                  >
                                    {payment.reference}
                                  </TableCell>
                                  <TableCell data-label="Person">
                                    <Entity id={payment.discordId} kind="discord" />
                                  </TableCell>
                                  <TableCell data-label="Days" className="text-right tnum">
                                    {payment.days}
                                  </TableCell>
                                  <TableCell data-label="Amount" className="text-right tnum">
                                    <div className="flex flex-col items-end">
                                      <span>{euros(payment.amountCents)}</span>
                                      {payment.donationCents > 0 ? (
                                        <span
                                          className="text-xs text-muted-foreground"
                                          title="Donation on top of the requested amount"
                                        >
                                          +{euros(payment.donationCents)}
                                        </span>
                                      ) : null}
                                    </div>
                                  </TableCell>
                                  <TableCell data-label="Status">
                                    <div className="flex items-center gap-1">
                                      <StatusBadge
                                        tone={late ? "warn" : (state?.tone ?? "idle")}
                                        tipContent={state?.title ?? "This interface does not know this status."}
                                      >
                                        {state?.label ?? payment.status}
                                      </StatusBadge>
                                      {late ? (
                                        <StatusBadge
                                          tone="warn"
                                          tipContent="The deadline has passed but the status still reads OPEN - the bot's cleanup run has not touched it yet."
                                        >
                                          overdue
                                        </StatusBadge>
                                      ) : null}
                                    </div>
                                  </TableCell>
                                  <TableCell data-label="Deadline" className="text-muted-foreground tnum">
                                    {dateTime(payment.expires)}
                                  </TableCell>
                                  <TableCell data-label="Paid" className="text-muted-foreground tnum">
                                    {dateTime(payment.settled)}
                                  </TableCell>
                                  <TableCell>
                                    <div className="flex items-center justify-end gap-1">
                                      {payment.shareUrl ? (
                                        <Button asChild variant="ghost" size="sm">
                                          <a
                                            href={payment.shareUrl}
                                            target="_blank"
                                            rel="noreferrer"
                                            title={payment.shareUrl}
                                          >
                                            <ArrowSquareOutIcon aria-hidden />
                                            Tab
                                          </a>
                                        </Button>
                                      ) : (
                                        <span
                                          className="text-xs text-muted-foreground"
                                          title="No bunq.me address stands in the row for this request."
                                        >
                                          {"\u2013"}
                                        </span>
                                      )}
                                      {/* Only an OPEN request can be settled. */}
                                      {payment.status === "OPEN" ? (
                                        <SettleAction reference={payment.reference} />
                                      ) : null}
                                    </div>
                                  </TableCell>
                                </TableRow>
                              )
                            })}
                      </TableBody>
                    </Table>
                  )}
                </CardContent>
              </Card>
            </>
          )
        }}
      </QueryState>
    </div>
  )
}

/** Ten rows of nothing while the record is read. */
const WAITING_ENTRIES = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]

/**
 * The audit log.
 *
 * Both filters are exact matches, so actions are picked from the rows present and the subject is submitted.
 */
/** A journal line's values by key; a line from before typed values keeps its one sentence under `detail`. */
function JournalFacts({ facts }: { facts: Record<string, unknown> }) {
  const shown = Object.entries(facts).filter(([key]) => key !== "target")
  if (shown.length === 0) return <>{"\u2013"}</>
  if (shown.length === 1 && shown[0][0] === "detail") return <>{factText(shown[0][1])}</>
  return (
    <span className="flex flex-wrap gap-x-3">
      {shown.map(([key, value]) => (
        <span key={key}>
          <span className="text-foreground">{key}</span> {factText(value)}
        </span>
      ))}
    </span>
  )
}

function factText(value: unknown): string {
  if (Array.isArray(value)) return value.map(factText).join(", ")
  if (typeof value === "string") return value
  if (typeof value === "number" || typeof value === "boolean") return String(value)
  return JSON.stringify(value)
}

export function JournalPage() {
  /** The unfiltered query, for the options; it shares its key with the filtered one while no filter is set. */
  const all = useJournal("", "")
  const [action, setAction] = useState("")
  const [subject, setSubject] = useState("")
  const [typed, setTyped] = useState("")
  const entries = useJournal(action, subject)
  const actions = [...new Set((all.data ?? []).map((entry) => entry.action))].toSorted()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="Journal" />

      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium">Entries</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div className="flex flex-wrap items-end gap-4">
            <div className="flex w-full min-w-0 flex-col gap-1.5 sm:w-auto">
              <Label htmlFor="journal-action">Action</Label>
              <Select
                value={action === "" ? "ALL" : action}
                onValueChange={(value) => setAction(value === "ALL" ? "" : value)}
              >
                <SelectTrigger id="journal-action" className="w-full sm:w-64">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="ALL">all</SelectItem>
                  {actions.map((value) => (
                    <SelectItem key={value} value={value}>
                      {value}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            {/* The field takes its own row on a phone; from `sm` it has a fixed width. */}
            <form
              className="flex w-full flex-wrap items-end gap-2"
              onSubmit={(event) => {
                event.preventDefault()
                setSubject(typed.trim())
              }}
            >
              <div className="flex w-full min-w-0 flex-col gap-1.5 sm:w-auto">
                <Label htmlFor="journal-subject">Discord id concerned</Label>
                <Input
                  id="journal-subject"
                  value={typed}
                  onChange={(event) => setTyped(event.target.value)}
                  placeholder="exact id…"
                  className="w-full font-mono sm:w-64"
                  autoComplete="off"
                  spellCheck={false}
                />
              </div>
              <Button type="submit" variant="outline">
                <MagnifyingGlassIcon aria-hidden />
                Filter
              </Button>
              {subject ? (
                <Button
                  type="button"
                  variant="ghost"
                  onClick={() => {
                    setSubject("")
                    setTyped("")
                  }}
                >
                  Reset
                </Button>
              ) : null}
            </form>
          </div>

          {actions.length === 0 && !all.isPending && !all.error ? (
            <p className="text-xs text-muted-foreground">
              The selector above lists only actions that occur in the loaded entries - none occurs yet.
            </p>
          ) : null}

          <QueryState
            query={entries}
            empty={{
              title: "No entry",
              note: 'Nothing in the record matches these filters. Both compare exactly, not partially - a typo in the id looks exactly like "nothing happened".',
            }}
            isEmpty={(list: JournalEntry[]) => list.length === 0}
          >
            {(list) => (
              <>
                <Table className="steward-table">
                  <TableHeader>
                    <TableRow>
                      <TableHead className="w-[13rem]">When</TableHead>
                      <TableHead className="w-[12rem]">Action</TableHead>
                      <TableHead className="w-[16rem]">Triggered by</TableHead>
                      <TableHead className="w-[14rem]">Concerns</TableHead>
                      <TableHead>Detail</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {list === undefined
                      ? WAITING_ENTRIES.map((index) => (
                          <TableRow key={index}>
                            <TableCell data-label="When">
                              <SkeletonText width="long" />
                            </TableCell>
                            <TableCell data-label="Action">
                              <SkeletonText width="medium" />
                            </TableCell>
                            <TableCell data-label="Triggered by">
                              <div className="flex items-center gap-2">
                                <Skeleton className="size-6 shrink-0 rounded-full" />
                                <SkeletonText width="long" className="max-w-[8rem]" />
                              </div>
                            </TableCell>
                            <TableCell data-label="Concerns">
                              <div className="flex items-center gap-2">
                                <Skeleton className="size-6 shrink-0 rounded-full" />
                                <SkeletonText width="long" className="max-w-[7rem]" />
                              </div>
                            </TableCell>
                            <TableCell data-label="Detail">
                              <SkeletonText width="full" />
                            </TableCell>
                          </TableRow>
                        ))
                      : list.map((entry) => (
                          <TableRow key={entry.id}>
                            <TableCell data-label="When" className="text-muted-foreground tnum" title={entry.occurred}>
                              {dateTime(entry.occurred)}
                            </TableCell>
                            {/* Printed raw, as the row carries it and as the bot's log names it. */}
                            <TableCell data-label="Action" className="font-medium">
                              {entry.action}
                            </TableCell>
                            {/* A profile, never an id; no admin at all is Steward's own mark. */}
                            <TableCell data-label="Triggered by" className="text-muted-foreground">
                              <Actor kind={entry.actor.kind} id={entry.actor.person ?? ""} />
                            </TableCell>
                            <TableCell data-label="Concerns" className="text-muted-foreground">
                              {entry.subject ? (
                                <Entity id={entry.subject} />
                              ) : entry.mcUuid ? (
                                <Entity id={entry.mcUuid} kind="minecraft" />
                              ) : typeof entry.facts.target === "string" ? (
                                <Entity id={entry.facts.target} />
                              ) : null}
                            </TableCell>
                            {/* The line's values, wrapping where TableCell would not. */}
                            <TableCell data-label="Detail" className="text-muted-foreground whitespace-normal">
                              <JournalFacts facts={entry.facts} />
                            </TableCell>
                          </TableRow>
                        ))}
                  </TableBody>
                </Table>
                <p className="text-xs text-muted-foreground">
                  {list === undefined ? (
                    <SkeletonText className="w-64" />
                  ) : (
                    <>
                      {count(list.length)} entries. This query hands out no more than 200 - paging through the whole
                      record is not something the API knows yet.
                    </>
                  )}
                </p>
              </>
            )}
          </QueryState>
        </CardContent>
      </Card>
    </div>
  )
}
