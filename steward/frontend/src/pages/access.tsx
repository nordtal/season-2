import { TERM_ICON } from "@/components/steward/term-icons"
import { Link, useNavigate } from "@tanstack/react-router"
import { useState, type ReactNode } from "react"

import type { Person } from "@/lib/api"
import { count, dateTime } from "@/lib/format"
import { Hint, Tip } from "@/components/steward/status"
import { span, t } from "@/lib/texts"
import { useNow } from "@/lib/use-now"
import { usePeople } from "@/lib/queries"
import { Entity } from "@/components/steward/entity"
import { DataTable, type Column, type Sort } from "@/components/steward/data-table"
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
  PlaytimeDialog,
  RoleBadges,
  hasRole,
} from "@/pages/access-person"
import { AuraDialog, SpinsDialog } from "@/pages/person-smp"

/** The Users page: who may join, and why. Payments and Journal share its vocabulary. */

const PEOPLE_PAGE_SIZE = 20

type LinkFilter = "" | "linked" | "unlinked"

/** What each sortable heading orders by. Online is later than any time seen, and never is earlier than any. */
const SORTS: Record<string, (person: Person) => number> = {
  seen: (person) => (person.online ? Infinity : person.lastSeen ? Date.parse(person.lastSeen) : -Infinity),
  access: (person) => (person.accessUntil ? Date.parse(person.accessUntil) : -Infinity),
  playtime: (person) => person.playtimeSeconds ?? 0,
  aura: (person) => person.aura,
  spins: (person) => person.spins,
}

const BY_SEEN: Sort = { key: "seen", descending: true }

function sorted(rows: Person[], sort: Sort): Person[] {
  const value = SORTS[sort.key] ?? SORTS.seen
  const direction = sort.descending ? -1 : 1
  return rows.toSorted((a, b) => {
    const [left, right] = [value(a), value(b)]
    return left === right ? 0 : left < right ? -direction : direction
  })
}

/** Online, or when the person was last online, or never. */
function lastSeen(person: Person): ReactNode {
  if (person.online) return t("steward.people.online")
  if (person.lastSeen)
    return <Tip content={dateTime(person.lastSeen)}>{t("steward.people.seen-at", { at: person.lastSeen })}</Tip>
  return t("steward.people.never-seen")
}

/**
 * The roster's columns. A phone's card holds the person on its first line, access on its second and the three
 * figures on its third.
 */
function peopleColumns(list: Person[], now: number): Column<Person>[] {
  return [
    {
      head: t("steward.people.person"),
      phone: { line: 1, side: "wide" },
      waiting: (
        <div className="flex items-center gap-2">
          <Skeleton className="size-8 shrink-0 rounded-full" />
          <SkeletonText width="full" className="max-w-52" />
        </div>
      ),
      render: (person) => (
        // One line: a long name gives way with an ellipsis, and the badges wrap only once the name would be cut below its basis.
        <div className="flex min-w-0 max-w-person flex-wrap items-center gap-x-2 gap-y-1 py-1">
          <PersonLink person={person} />
          <span className="flex shrink-0 items-center gap-2 empty:hidden">
            {/* "Member" is left unsaid. LEFT and BANNED get a badge. */}
            {person.memberState !== "MEMBER" ? <MemberBadge state={person.memberState} /> : null}
            {/* "Not linked" matters only while it keeps a paying person out. */}
            {!person.minecraftUuid && person.accessActive ? <LinkBadge person={person} /> : null}
            {hasRole(person) ? <RoleBadges person={person} people={list} /> : null}
          </span>
          <span className="shrink-0 text-xs text-muted-foreground @rows:hidden">{lastSeen(person)}</span>
        </div>
      ),
    },
    {
      head: t("steward.people.access"),
      sortKey: "access",
      phone: { line: 2, side: "start" },
      waiting: <Skeleton className="h-5 w-60 max-w-full rounded-full" />,
      render: (person) => (
        <Value person={person} dialog="access">
          <AccessBadge person={person} now={now} tip={false} />
        </Value>
      ),
    },
    {
      // The prestige tier derives from this number, so it is the one lever an admin has. A phone draws all three figures here.
      head: t("steward.people.playtime"),
      sortKey: "playtime",
      phone: { line: 3, side: "wide" },
      waiting: <SkeletonText width="medium" className="min-w-12" />,
      render: (person) => (
        <div className="flex flex-wrap items-center gap-1.5">
          <Figure person={person} term="playtime">
            {span(person.playtimeSeconds, "minutes")}
          </Figure>
          <span className="contents @rows:hidden">
            <Figure person={person} term="aura">
              {count(person.aura)}
            </Figure>
            <Figure person={person} term="spins">
              {count(person.spins)}
            </Figure>
          </span>
        </div>
      ),
    },
    {
      head: t("steward.people.aura"),
      sortKey: "aura",
      phone: null,
      waiting: <SkeletonText width="short" className="min-w-8" />,
      render: (person) => (
        <Figure person={person} term="aura">
          {count(person.aura)}
        </Figure>
      ),
    },
    {
      head: t("steward.people.spins"),
      sortKey: "spins",
      phone: null,
      waiting: <SkeletonText width="short" className="min-w-8" />,
      render: (person) => (
        <Figure person={person} term="spins">
          {count(person.spins)}
        </Figure>
      ),
    },
    {
      head: t("steward.people.last-seen"),
      sortKey: "seen",
      // A phone draws it under the person.
      phone: null,
      className: "text-muted-foreground",
      render: (person) => (
        <span className={person.online ? "font-medium text-success" : undefined}>{lastSeen(person)}</span>
      ),
    },
    { head: "", phone: "action", render: (person) => <PersonActions person={person} menu /> },
  ]
}

/** Whether a name inside the element is cut off by its ellipsis. */
function cutOff(element: Element): boolean {
  return [...element.querySelectorAll(".truncate")].some((name) => name.scrollWidth > name.clientWidth)
}

/** The person as one element that opens their page. A name cut off by the width shows in full in the tip. */
function PersonLink({ person }: { person: Person }) {
  const [cut, setCut] = useState(false)
  const discordName = person.discordDisplayName ?? person.discordUsername
  return (
    <Hint
      tip={
        cut ? (
          <span className="flex flex-col">
            {discordName ? <span>{discordName}</span> : null}
            {person.mcName ? <span className="text-muted-foreground">{person.mcName}</span> : null}
          </span>
        ) : undefined
      }
    >
      <Link
        to="/access/$id"
        params={{ id: person.discordId }}
        onPointerEnter={(event) => setCut(cutOff(event.currentTarget))}
        onFocus={(event) => setCut(cutOff(event.currentTarget))}
        className="flex min-w-0 max-w-max grow basis-16 rounded-sm underline-offset-4 tap-target hover:underline"
      >
        <Entity id={person.discordId} kind="discord" face="both" interactive={false} />
      </Link>
    </Hint>
  )
}

type ValueDialog = "access" | "playtime" | "aura" | "spins"

/** A value of the row that opens its own dialog, which is rendered beside the button and never inside it. */
function Value({
  person,
  dialog,
  className,
  children,
}: {
  person: Person
  dialog: ValueDialog
  className?: string
  children: ReactNode
}) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <button
        type="button"
        aria-haspopup="dialog"
        onClick={() => setOpen(true)}
        className={
          "inline-flex min-h-0 max-w-full items-center rounded-full outline-none tap-target hover:opacity-80 " +
          "focus-visible:ring-3 focus-visible:ring-ring/50 " +
          (className ?? "")
        }
      >
        {children}
      </button>
      {open && dialog === "access" ? <GrantDialog person={person} open onOpenChange={setOpen} /> : null}
      {open && dialog === "playtime" ? <PlaytimeDialog person={person} open onOpenChange={setOpen} /> : null}
      {open && dialog === "aura" ? <AuraDialog person={person} open onOpenChange={setOpen} /> : null}
      {open && dialog === "spins" ? <SpinsDialog person={person} open onOpenChange={setOpen} /> : null}
    </>
  )
}

/** One figure of a row in the pill every figure shares. Its icon names it on a phone, which draws no heading. */
function Figure({
  person,
  term,
  children,
}: {
  person: Person
  term: "playtime" | "aura" | "spins"
  children: ReactNode
}) {
  const Icon = TERM_ICON[term]
  return (
    <Value
      person={person}
      dialog={term}
      className="h-6 gap-1 border border-border bg-muted/40 px-2 text-sm tabular-nums hover:bg-muted"
    >
      {/* Hidden and still named on a desktop, so the button says what it sets. */}
      <Icon
        className="size-3.5 shrink-0 text-muted-foreground @rows:sr-only"
        role="img"
        aria-label={t(`steward.people.${term}`)}
      />
      {children}
    </Value>
  )
}

/**
 * The roster: everyone the bot knows. Every write is a request that the bot carries out, as `/access` in Discord does.
 */
export function AccessPage() {
  const people = usePeople()
  const navigate = useNavigate()
  const [needle, setNeedle] = useState("")
  const [onlyWithAccess, setOnlyWithAccess] = useState(false)
  const [onlyAdmins, setOnlyAdmins] = useState(false)
  const [onlySupporters, setOnlySupporters] = useState(false)
  const [link, setLink] = useState<LinkFilter>("")
  const [page, setPage] = useState(0)
  const [sort, setSort] = useState<Sort>(BY_SEEN)
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
      <PageHeader title={t("steward.people.title")} />

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
              <FilterOption
                icon={TERM_ICON.grantAccess}
                pressed={onlyWithAccess}
                onPressedChange={refilter(setOnlyWithAccess)}
              >
                {t("steward.people.with-access")}
              </FilterOption>
              <FilterOption icon={TERM_ICON.admin} pressed={onlyAdmins} onPressedChange={refilter(setOnlyAdmins)}>
                {t("steward.people.admins")}
              </FilterOption>
              <FilterOption
                icon={TERM_ICON.supporter}
                pressed={onlySupporters}
                onPressedChange={refilter(setOnlySupporters)}
              >
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
            const filtered = list.filter(
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
            const rows = sorted(filtered, sort)
            if (rows.length === 0) {
              return <Empty title={t("steward.people.no-match")} />
            }
            const pageCount = Math.max(1, Math.ceil(rows.length / PEOPLE_PAGE_SIZE))
            const clampedPage = Math.min(page, pageCount - 1)
            const paged = rows.slice(clampedPage * PEOPLE_PAGE_SIZE, clampedPage * PEOPLE_PAGE_SIZE + PEOPLE_PAGE_SIZE)
            return (
              <>
                <DataTable
                  columns={peopleColumns(list, now)}
                  rows={paged}
                  rowKey={(person) => person.discordId}
                  onRowClick={(person) => navigate({ to: "/access/$id", params: { id: person.discordId } })}
                  sort={sort}
                  onSortChange={(next) => {
                    setSort(next)
                    setPage(0)
                  }}
                />
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
