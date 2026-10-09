import { HourglassIcon, MagnifyingGlassIcon } from "@phosphor-icons/react"
import { Link } from "@tanstack/react-router"
import type { ReactNode } from "react"
import { useState } from "react"

import type { Person } from "@/lib/api"
import { span, t } from "@/lib/texts"
import { useNow } from "@/lib/use-now"
import { usePeople } from "@/lib/queries"
import { Entity } from "@/components/steward/entity"
import { PageHeader } from "@/components/steward/page-header"
import { Empty, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import {
  AccessBadge,
  GrantDialog,
  LinkBadge,
  MemberBadge,
  PersonActions,
  RoleBadges,
  hasRole,
} from "@/pages/access-person"

/**
 * The Users page: who may join, and why they may.
 *
 * Payments and Journal are pages of their own and share its vocabulary: the chain request, tab, paid, access.
 */

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
      <TableCell data-label={t("steward.people.access")} data-phone="inline">
        <Skeleton className="h-5 w-[15rem] max-w-full rounded-full" />
      </TableCell>
      <TableCell data-label={t("steward.people.minecraft")} data-phone="inline">
        <div className="flex items-center gap-2">
          <Skeleton className="size-5 shrink-0 rounded-sm" />
          <SkeletonText width="full" className="max-w-[6.5rem]" />
        </div>
      </TableCell>
      <TableCell data-label={t("steward.people.roles")} data-phone="inline">
        <Skeleton className="h-5 w-[7rem] max-w-full rounded-full" />
      </TableCell>
      <TableCell data-label={t("steward.people.playtime")} data-phone="inline">
        <SkeletonText width="medium" className="min-w-[3rem]" />
      </TableCell>
      <TableCell />
    </TableRow>
  )
}

/**
 * The roster: everyone the bot knows, and what they may.
 *
 * Every write here is a request the bot carries out, as `/access` in Discord does; a name opens the person's page.
 */
export function AccessPage() {
  const people = usePeople()
  const [needle, setNeedle] = useState("")
  const [onlyWithAccess, setOnlyWithAccess] = useState(false)
  const [page, setPage] = useState(0)
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
          empty={{ title: t("steward.people.nobody") }}
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
              return <Empty title={t("steward.people.no-match")} />
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
                          {/* The closed face, since the link is the way to everything else about them. */}
                          <Link
                            to="/access/$id"
                            params={{ id: person.discordId }}
                            className="min-w-0 underline-offset-4 tap-target hover:text-primary hover:underline"
                          >
                            <Entity id={person.discordId} kind="discord" interactive={false} />
                          </Link>
                          {/* "Member" is left unsaid; LEFT and BANNED get a badge beside the name. */}
                          {person.memberState !== "MEMBER" ? <MemberBadge state={person.memberState} /> : null}
                        </div>
                      </TableCell>
                      {/* On a phone the badges and the face read on their own, so they share lines without labels. */}
                      <TableCell data-label={t("steward.people.access")} data-phone="inline">
                        <AccessBadge person={person} now={now} />
                      </TableCell>
                      {/* "Not linked" is only worth a place on a phone while it keeps a paying person out. */}
                      <TableCell
                        data-label={t("steward.people.minecraft")}
                        data-phone={person.minecraftUuid || person.accessActive ? "inline" : "off"}
                      >
                        {person.minecraftUuid ? (
                          <Entity id={person.minecraftUuid} kind="minecraft" />
                        ) : (
                          <LinkBadge person={person} />
                        )}
                      </TableCell>
                      <TableCell data-label={t("steward.people.roles")} data-phone={hasRole(person) ? "inline" : "off"}>
                        <RoleBadges person={person} people={list} />
                      </TableCell>
                      <TableCell
                        data-label={t("steward.people.playtime")}
                        data-phone={person.playtimeSeconds ? "inline" : "off"}
                      >
                        {/* `playtime` draws the dash for somebody who has never been online. */}
                        <span className="flex items-center gap-1 text-sm tabular-nums">
                          {/* The card's stand-in for the column's heading, which a phone does not draw. */}
                          <HourglassIcon
                            className="size-3.5 shrink-0 text-muted-foreground @rows:hidden"
                            role="img"
                            aria-label={t("steward.people.playtime")}
                          />
                          {span(person.playtimeSeconds, "minutes")}
                        </span>
                      </TableCell>
                      {/* Off on a phone, where the card is a link's worth and the page has the actions as buttons. */}
                      <TableCell data-phone="off">
                        <PersonActions person={person} menu />
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
    </div>
  )
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
