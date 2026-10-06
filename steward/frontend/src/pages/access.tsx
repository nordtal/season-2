import {
  ClockCounterClockwiseIcon,
  CrownCrossIcon,
  CrownIcon,
  HourglassIcon,
  LinkBreakIcon,
  PackageIcon,
  MagnifyingGlassIcon,
  ShieldSlashIcon,
  UserPlusIcon,
} from "@phosphor-icons/react"
import type { ReactNode } from "react"
import { useState } from "react"

import { adminsBelow } from "@/lib/admin-tree"
import type { Person } from "@/lib/api"
import { span, t } from "@/lib/texts"
import { useNow } from "@/lib/use-now"
import { useMe, usePeople } from "@/lib/queries"
import { Entity } from "@/components/steward/entity"
import { PageHeader } from "@/components/steward/page-header"
import { RowActions, type RowAction } from "@/components/steward/row-actions"
import { StatusBadge } from "@/components/steward/status"
import { Empty, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { ResponsiveDialog, ResponsiveDialogContent } from "@/components/ui/responsive-dialog"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import {
  GrantDialog,
  MakeAdminDialog,
  MemberBadge,
  PackExemptionDialog,
  PersonGrants,
  PlaytimeDialog,
  RevokeAdminDialog,
  RevokeDialog,
  UnlinkDialog,
  personName,
} from "@/pages/access-person"

/**
 * The Users page: who may join, and why they may.
 *
 * Payments and Journal are pages of their own and share its vocabulary: the chain request, tab, paid, access.
 */

/**
 * Access, from `accessActive` (the login decision) and `accessUntil` (the latest period on record).
 *
 * Inactive with a future period is revoked or not yet started; the tooltip names both.
 */
function AccessBadge({ person, now }: { person: Person; now: number }) {
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
function LinkBadge({ person }: { person: Person }) {
  return (
    <StatusBadge
      tone={person.accessActive ? "warn" : "idle"}
      tipContent={t("steward.people.not-linked-tip", { paid: person.accessActive })}
    >
      {t("steward.people.not-linked")}
    </StatusBadge>
  )
}

/** Rows per page of the People table, paged after filtering. */
const PEOPLE_PAGE_SIZE = 20

/** The roster's header row and column widths, shared by the real rows and the waiting ones. */
function PeopleTable({ children }: { children: ReactNode }) {
  return (
    <Table className="steward-table">
      <TableHeader>
        <TableRow>
          <TableHead className="w-[16rem]">{t("steward.people.person")}</TableHead>
          <TableHead className="w-[20rem]">
            <AccessColumnHead />
          </TableHead>
          <TableHead className="w-[9rem]">{t("steward.people.minecraft")}</TableHead>
          <TableHead className="w-[9rem]">{t("steward.people.roles")}</TableHead>
          {/* The prestige tier is derived from this number, so it is the one lever an admin has. */}
          <TableHead className="w-[7rem]">{t("steward.people.playtime")}</TableHead>
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
      <TableCell className="font-medium">
        <div className="flex items-center gap-2">
          <Skeleton className="size-6 shrink-0 rounded-full" />
          <SkeletonText width="full" className="max-w-[13rem]" />
        </div>
      </TableCell>
      <TableCell data-label={t("steward.people.access")}>
        <Skeleton className="h-5 w-[15rem] max-w-full rounded-full" />
      </TableCell>
      <TableCell data-label={t("steward.people.minecraft")}>
        <div className="flex items-center gap-2">
          <Skeleton className="size-5 shrink-0 rounded-sm" />
          <SkeletonText width="full" className="max-w-[6.5rem]" />
        </div>
      </TableCell>
      <TableCell data-label={t("steward.people.roles")}>
        <Skeleton className="h-5 w-[7rem] max-w-full rounded-full" />
      </TableCell>
      <TableCell data-label={t("steward.people.playtime")}>
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
      <PageHeader title={t("steward.people.title")} actions={<GrantDialog />} />

      <div className="flex flex-col gap-3">
        {/* Stacked below `sm`, one row above, with `min-w-0` on the input so it can shrink. */}
        <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-center sm:gap-4">
          <div className="flex w-full min-w-0 items-center gap-2 sm:min-w-64 sm:flex-1">
            <MagnifyingGlassIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
            <Input
              value={needle}
              onChange={(event) => changeNeedle(event.target.value)}
              /** Short enough for 390px; the long sentence is the accessible name. */
              placeholder={t("steward.people.filter")}
              aria-label={t("steward.people.filter-name")}
              className="min-w-0"
              autoComplete="off"
            />
          </div>
          <div className="flex min-w-0 items-center gap-2">
            <Switch id="only-with-access" checked={onlyWithAccess} onCheckedChange={changeOnlyWithAccess} />
            <Label htmlFor="only-with-access">{t("steward.people.with-access")}</Label>
          </div>
        </div>

        <QueryState
          query={people}
          empty={{
            title: t("steward.people.nobody"),
            note: t("steward.people.nobody-note"),
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
                  title={t("steward.people.no-match")}
                  note={t("steward.people.no-match-note", { withAccess: onlyWithAccess })}
                />
              )
            }
            /** Paged after filtering, so a search reaches the whole roster. */
            const pageCount = Math.max(1, Math.ceil(rows.length / PEOPLE_PAGE_SIZE))
            const clampedPage = Math.min(page, pageCount - 1)
            const paged = rows.slice(clampedPage * PEOPLE_PAGE_SIZE, clampedPage * PEOPLE_PAGE_SIZE + PEOPLE_PAGE_SIZE)
            return (
              <>
                <PeopleTable>
                  {paged.map((person) => (
                    <TableRow key={person.discordId}>
                      {/* No `data-label`, so on a phone the name heads the card on a line of its own. */}
                      <TableCell className="font-medium">
                        <div className="flex flex-wrap items-center gap-2">
                          <Entity id={person.discordId} kind="discord" />
                          {/* "Member" is left unsaid; LEFT and BANNED get a badge beside the name. */}
                          {person.memberState !== "MEMBER" ? <MemberBadge state={person.memberState} /> : null}
                        </div>
                      </TableCell>
                      <TableCell data-label={t("steward.people.access")}>
                        <AccessBadge person={person} now={now} />
                      </TableCell>
                      {/* "Not linked" is only worth a line on a phone while it keeps a paying person out. */}
                      <TableCell
                        data-label={t("steward.people.minecraft")}
                        data-phone={person.minecraftUuid || person.accessActive ? undefined : "off"}
                      >
                        {person.minecraftUuid ? (
                          <Entity id={person.minecraftUuid} kind="minecraft" />
                        ) : (
                          <LinkBadge person={person} />
                        )}
                      </TableCell>
                      <TableCell
                        data-label={t("steward.people.roles")}
                        data-phone={person.donor || person.admin || person.packExemptAt ? undefined : "off"}
                      >
                        <div className="flex items-center gap-1">
                          {person.donor ? (
                            <StatusBadge tone="idle" tipContent={t("steward.people.supporter-tip")}>
                              {t("steward.people.supporter")}
                            </StatusBadge>
                          ) : null}
                          {person.admin ? (
                            <StatusBadge tone="idle" tipContent={grantedByText(person, list)}>
                              {t("steward.people.admin")}
                            </StatusBadge>
                          ) : null}
                          {person.packExemptAt ? (
                            <StatusBadge tone="warn" tipContent={packExemptText(person, list)}>
                              {t("steward.people.no-pack")}
                            </StatusBadge>
                          ) : null}
                          {!person.donor && !person.admin && !person.packExemptAt ? (
                            <span className="text-xs text-muted-foreground">{"\u2013"}</span>
                          ) : null}
                        </div>
                      </TableCell>
                      <TableCell
                        data-label={t("steward.people.playtime")}
                        data-phone={person.playtimeSeconds ? undefined : "off"}
                      >
                        {/* `playtime` draws the dash for somebody who has never been online. */}
                        <span className="text-sm tabular-nums">{span(person.playtimeSeconds, "minutes")}</span>
                      </TableCell>
                      <TableCell>
                        <RowActions
                          menu
                          label={t("steward.people.actions-for", { name: personName(person) })}
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
                  {/* The one count of the list: how many match, and which of them this page shows. */}
                  <p className="text-xs text-muted-foreground tnum">
                    {pageCount > 1
                      ? t("steward.people.range", {
                          from: clampedPage * PEOPLE_PAGE_SIZE + 1,
                          to: clampedPage * PEOPLE_PAGE_SIZE + paged.length,
                          total: rows.length,
                        })
                      : t("steward.people.count", { count: rows.length })}
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
                        {t("steward.people.previous")}
                      </Button>
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled={clampedPage >= pageCount - 1}
                        onClick={() => setPage((current) => current + 1)}
                      >
                        {t("steward.people.next")}
                      </Button>
                    </div>
                  ) : null}
                </div>
              </>
            )
          }}
        </QueryState>
      </div>

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
          {t("steward.people.periods")}
        </Button>
      ),
    })
  }

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

/** The header of the access column, with the reason its two halves disagree. */
function AccessColumnHead() {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span tabIndex={0} className="underline decoration-dotted underline-offset-4">
          {t("steward.people.access")}
        </span>
      </TooltipTrigger>
      <TooltipContent className="max-w-xs">{t("steward.people.access-tip")}</TooltipContent>
    </Tooltip>
  )
}
