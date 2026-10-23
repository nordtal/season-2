import { TERM_ICON } from "@/components/steward/term-icons"
import { Link } from "@tanstack/react-router"
import { useState } from "react"

import type { Person } from "@/lib/api"
import { count } from "@/lib/format"
import { span, t } from "@/lib/texts"
import { useNow } from "@/lib/use-now"
import { usePeople } from "@/lib/queries"
import { Entity } from "@/components/steward/entity"
import { DataTable, type Column } from "@/components/steward/data-table"
import { FilterBar, FilterChoice, FilterMenu, FilterOption, SearchField } from "@/components/steward/filter-bar"
import { PageHeader } from "@/components/steward/page-header"
import { Empty, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import {
  AccessBadge,
  GrantDialog,
  LinkBadge,
  MemberBadge,
  PersonActions,
  RoleBadges,
  hasRole,
} from "@/pages/access-person"

/** The Users page: who may join, and why. Payments and Journal share its vocabulary. */

const PEOPLE_PAGE_SIZE = 20

type LinkFilter = "" | "linked" | "unlinked"

/**
 * The roster's columns. On a phone the person and the roles head the card, access and playtime follow, and aura and
 * extra spins close it where they are not zero.
 */
function peopleColumns(list: Person[], now: number): Column<Person>[] {
  return [
    {
      head: t("steward.people.person"),
      grow: true,
      phone: { line: 1, side: "start" },
      className: "font-medium",
      waiting: (
        <div className="flex items-center gap-2">
          <Skeleton className="size-6 shrink-0 rounded-full" />
          <SkeletonText width="full" className="max-w-[13rem]" />
        </div>
      ),
      render: (person) => (
        <>
          <div className="hidden flex-wrap items-center gap-2 @rows:flex">
            <Link
              to="/access/$id"
              params={{ id: person.discordId }}
              className="min-w-0 underline-offset-4 tap-target hover:text-primary hover:underline"
            >
              <Entity id={person.discordId} kind="discord" interactive={false} />
            </Link>
            {/* "Member" is left unsaid. LEFT and BANNED get a badge. */}
            {person.memberState !== "MEMBER" ? <MemberBadge state={person.memberState} /> : null}
          </div>
          <Entity id={person.discordId} kind="discord" face="both" className="@rows:hidden" />
        </>
      ),
    },
    {
      head: t("steward.people.access"),
      phone: { line: 2, side: "start" },
      waiting: <Skeleton className="h-5 w-[15rem] max-w-full rounded-full" />,
      render: (person) => <AccessBadge person={person} now={now} />,
    },
    {
      head: t("steward.people.minecraft"),
      // On a phone a missing link is a badge beside the roles.
      phone: null,
      waiting: (
        <div className="flex items-center gap-2">
          <Skeleton className="size-5 shrink-0 rounded-sm" />
          <SkeletonText width="full" className="max-w-[6.5rem]" />
        </div>
      ),
      render: (person) =>
        person.minecraftUuid ? <Entity id={person.minecraftUuid} kind="minecraft" /> : <LinkBadge person={person} />,
    },
    {
      head: t("steward.people.roles"),
      phone: { line: 1, side: "end" },
      waiting: <Skeleton className="h-5 w-[7rem] max-w-full rounded-full" />,
      render: (person) => (
        <>
          <div className="flex flex-wrap items-center justify-end gap-1 @rows:justify-start">
            {person.memberState !== "MEMBER" ? (
              <span className="contents @rows:hidden">
                <MemberBadge state={person.memberState} />
              </span>
            ) : null}
            {/* "Not linked" matters on a phone only while it keeps a paying person out. */}
            {!person.minecraftUuid && person.accessActive ? (
              <span className="contents @rows:hidden">
                <LinkBadge person={person} />
              </span>
            ) : null}
            {hasRole(person) ? (
              <RoleBadges person={person} people={list} className="justify-end @rows:justify-start" />
            ) : null}
          </div>
          {hasRole(person) ? null : (
            <span className="hidden text-xs text-muted-foreground @rows:inline">{"\u2013"}</span>
          )}
        </>
      ),
    },
    {
      // The prestige tier derives from this number, so it is the one lever an admin has.
      head: t("steward.people.playtime"),
      phone: { line: 2, side: "end" },
      waiting: <SkeletonText width="medium" className="min-w-[3rem]" />,
      render: (person) => (
        // `playtime` draws the dash for somebody never online.
        <span
          className={
            "flex items-center gap-1 text-sm tabular-nums" + (person.playtimeSeconds ? "" : " @max-rows:hidden")
          }
        >
          {/* The card's stand-in for the heading a phone does not draw. */}
          <TERM_ICON.playtime
            className="size-3.5 shrink-0 text-muted-foreground @rows:hidden"
            role="img"
            aria-label={t("steward.people.playtime")}
          />
          {span(person.playtimeSeconds, "minutes")}
        </span>
      ),
    },
    {
      head: t("steward.people.aura"),
      // A phone draws the line only for somebody who has aura or extra spins, since most people have neither.
      phone: (person) => (person.aura ? { line: 3, side: "start" } : null),
      waiting: <SkeletonText width="short" className="min-w-[2rem]" />,
      render: (person) => <SmpCount person={person} term="aura" />,
    },
    {
      head: t("steward.people.spins"),
      phone: (person) => (person.spins ? { line: 3, side: person.aura ? "end" : "start" } : null),
      waiting: <SkeletonText width="short" className="min-w-[2rem]" />,
      render: (person) => <SmpCount person={person} term="spins" />,
    },
    { head: "", phone: "action", render: (person) => <PersonActions person={person} menu /> },
  ]
}

/** One SMP figure of a row. */
function SmpCount({ person, term }: { person: Person; term: "aura" | "spins" }) {
  const Icon = TERM_ICON[term]
  return (
    <span className="flex items-center gap-1 text-sm tabular-nums">
      {/* The card's stand-in for the heading a phone does not draw. */}
      <Icon
        className="size-3.5 shrink-0 text-muted-foreground @rows:hidden"
        role="img"
        aria-label={t(`steward.people.${term}`)}
      />
      {count(person[term])}
    </span>
  )
}

/**
 * The roster: everyone the bot knows. Every write is a request that the bot carries out, as `/access` in Discord does.
 */
export function AccessPage() {
  const people = usePeople()
  const [needle, setNeedle] = useState("")
  const [onlyWithAccess, setOnlyWithAccess] = useState(false)
  const [onlyAdmins, setOnlyAdmins] = useState(false)
  const [onlySupporters, setOnlySupporters] = useState(false)
  const [link, setLink] = useState<LinkFilter>("")
  const [page, setPage] = useState(0)
  // One clock for the whole render, so two badges in a row cannot disagree about "now".
  const now = useNow()

  function changeNeedle(value: string) {
    setNeedle(value)
    setPage(0)
  }
  /** Every filter starts the roster again from its first page. */
  function refilter<V>(set: (value: V) => void): (value: V) => void {
    return (value) => {
      set(value)
      setPage(0)
    }
  }
  const active = [onlyWithAccess, onlyAdmins, onlySupporters, link !== ""].filter(Boolean).length

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t("steward.people.title")} actions={<GrantDialog />} />

      <div className="flex flex-col gap-3">
        <FilterBar>
          <SearchField
            value={needle}
            onValueChange={changeNeedle}
            placeholder={t("steward.people.filter")}
            label={t("steward.people.filter-name")}
          />
          <FilterMenu active={active}>
            <div className="flex flex-col">
              <FilterOption icon={TERM_ICON.grantAccess} pressed={onlyWithAccess} onPressedChange={refilter(setOnlyWithAccess)}>
                {t("steward.people.with-access")}
              </FilterOption>
              <FilterOption icon={TERM_ICON.admin} pressed={onlyAdmins} onPressedChange={refilter(setOnlyAdmins)}>
                {t("steward.people.admins")}
              </FilterOption>
              <FilterOption icon={TERM_ICON.supporter} pressed={onlySupporters} onPressedChange={refilter(setOnlySupporters)}>
                {t("steward.people.supporters")}
              </FilterOption>
            </div>
            <FilterChoice
              label={t("steward.people.minecraft")}
              value={link}
              onValueChange={refilter((value: string) =>
                setLink(value === "linked" || value === "unlinked" ? value : ""),
              )}
              options={[
                { value: "linked", label: t("steward.people.only-linked"), icon: TERM_ICON.link },
                { value: "unlinked", label: t("steward.people.only-unlinked"), icon: TERM_ICON.unlink },
              ]}
            />
          </FilterMenu>
        </FilterBar>

        <QueryState
          query={people}
          empty={{ title: t("steward.people.nobody") }}
          isEmpty={(list: Person[]) => list.length === 0}
        >
          {(list) => {
            if (list === undefined) {
              return (
                <>
                  <DataTable columns={peopleColumns([], now)} rows={undefined} rowKey={() => ""} />
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <SkeletonText className="w-44 text-xs" />
                  </div>
                </>
              )
            }
            const trimmed = needle.trim().toLowerCase()
            /** Matches both names and both ids, so an id from a log still finds the person. */
            const rows = list.filter(
              (person) =>
                (!onlyWithAccess || person.accessActive) &&
                (!onlyAdmins || person.admin) &&
                (!onlySupporters || person.donor) &&
                (link === "" || (link === "linked") === Boolean(person.minecraftUuid)) &&
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
            const pageCount = Math.max(1, Math.ceil(rows.length / PEOPLE_PAGE_SIZE))
            const clampedPage = Math.min(page, pageCount - 1)
            const paged = rows.slice(clampedPage * PEOPLE_PAGE_SIZE, clampedPage * PEOPLE_PAGE_SIZE + PEOPLE_PAGE_SIZE)
            return (
              <>
                <DataTable columns={peopleColumns(list, now)} rows={paged} rowKey={(person) => person.discordId} />
                <div className="flex flex-wrap items-center justify-between gap-2">
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
