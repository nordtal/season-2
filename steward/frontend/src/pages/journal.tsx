import { useState } from "react"

import type { JournalAction, JournalEntry } from "@/lib/api"
import { dateTime } from "@/lib/format"
import { useJournal } from "@/lib/queries"
import { choice, message, t } from "@/lib/texts"
import { Actor, Entity } from "@/components/steward/entity"
import { FilterBar, FilterSelect, SearchField } from "@/components/steward/filter-bar"
import { PageHeader } from "@/components/steward/page-header"
import { Panel } from "@/components/steward/panel"
import { QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

const JOURNAL_LIMIT = 200

const WAITING_ENTRIES = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]

/**
 * The audit log. Both filters are exact matches, so actions are picked from the rows present and the subject is submitted.
 */
function actionLabel(action: JournalAction): string {
  return t("journal.action", { action: choice(action) })
}

/** Returns whether a line has no values, so the phone card leaves it out. */
function restatesAction(entry: JournalEntry): boolean {
  return Object.keys(entry.line.args ?? {}).length === 0
}

/**
 * Returns whether the entry concerns someone other than the one who acted, which alone earns a line on a phone.
 */
function concernsAnother(entry: JournalEntry): boolean {
  if (entry.subject) return entry.subject !== entry.actor.person
  return entry.mcUuid !== undefined
}

/** Journal entries as rows, with ten waiting ones while `entries` is undefined. */
export function JournalTable({
  entries,
  concerns = true,
}: {
  entries: JournalEntry[] | undefined
  /** `false` on a person's page, where Detail needs the room. */
  concerns?: boolean
}) {
  const columns = {
    when: t("steward.journal.when"),
    action: t("steward.journal.action"),
    actor: t("steward.journal.actor"),
    concerns: t("steward.journal.concerns"),
    detail: t("steward.journal.detail"),
  }
  return (
    <Table className="steward-table table-fixed">
      <TableHeader>
        <TableRow>
          <TableHead className="w-[10.5rem]">{columns.when}</TableHead>
          <TableHead className="w-[11rem]">{columns.action}</TableHead>
          <TableHead className="w-[12rem]">{columns.actor}</TableHead>
          {concerns ? <TableHead className="w-[12rem]">{columns.concerns}</TableHead> : null}
          <TableHead>{columns.detail}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {entries === undefined
          ? WAITING_ENTRIES.map((index) => (
              <TableRow key={index}>
                <TableCell>
                  <SkeletonText width="long" />
                </TableCell>
                <TableCell data-phone="off">
                  <SkeletonText width="medium" />
                </TableCell>
                <TableCell data-label={columns.actor}>
                  <div className="flex items-center gap-2">
                    <Skeleton className="size-6 shrink-0 rounded-full" />
                    <SkeletonText width="long" className="max-w-[8rem]" />
                  </div>
                </TableCell>
                {concerns ? (
                  <TableCell data-label={columns.concerns}>
                    <div className="flex items-center gap-2">
                      <Skeleton className="size-6 shrink-0 rounded-full" />
                      <SkeletonText width="long" className="max-w-[7rem]" />
                    </div>
                  </TableCell>
                ) : null}
                <TableCell data-label={columns.detail}>
                  <SkeletonText width="full" />
                </TableCell>
              </TableRow>
            ))
          : entries.map((entry) => (
              <TableRow key={entry.id}>
                {/* No `data-label`: on a phone the time and action head the card. */}
                <TableCell title={entry.occurred}>
                  <span className="flex flex-wrap items-baseline gap-x-3">
                    <span className="text-muted-foreground tnum">{dateTime(entry.occurred)}</span>
                    <span className="font-medium @rows:hidden">{actionLabel(entry.action)}</span>
                  </span>
                </TableCell>
                <TableCell data-phone="off" className="font-medium" title={entry.action}>
                  {actionLabel(entry.action)}
                </TableCell>
                {/* A profile and never an id. No admin is Steward's own mark. */}
                <TableCell data-label={columns.actor} className="text-muted-foreground">
                  <Actor kind={entry.actor.kind} id={entry.actor.person ?? ""} />
                </TableCell>
                {concerns ? (
                  <TableCell
                    data-label={columns.concerns}
                    data-phone={concernsAnother(entry) ? undefined : "off"}
                    className="text-muted-foreground"
                  >
                    {entry.subject ? (
                      <Entity id={entry.subject} />
                    ) : entry.mcUuid ? (
                      <Entity id={entry.mcUuid} kind="minecraft" />
                    ) : null}
                  </TableCell>
                ) : null}
                <TableCell
                  data-label={columns.detail}
                  data-phone={restatesAction(entry) ? "off" : undefined}
                  className="text-muted-foreground whitespace-normal"
                >
                  {message(entry.line)}
                </TableCell>
              </TableRow>
            ))}
      </TableBody>
    </Table>
  )
}

/** Every part of the filter, empty, so the unfiltered query's key is the filtered one's. */
const NO_FILTER = { action: "", subject: "", reference: "", minDays: "" }

export function JournalPage() {
  const all = useJournal(NO_FILTER)
  const [action, setAction] = useState("")
  const [subject, setSubject] = useState("")
  const [typed, setTyped] = useState("")
  const [reference, setReference] = useState("")
  const [typedReference, setTypedReference] = useState("")
  const [minDays, setMinDays] = useState("")
  const [typedDays, setTypedDays] = useState("")
  const entries = useJournal({ action, subject, reference, minDays })
  const actions = [...new Set((all.data ?? []).map((entry) => entry.action))].toSorted()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t("steward.journal.title")} />

      <Panel title={t("steward.journal.entries")}>
        <FilterBar>
          {/* An exact id, so it filters on Enter. */}
          <SearchField
            value={typed}
            onValueChange={setTyped}
            onSubmit={setSubject}
            label={t("steward.journal.subject")}
            placeholder={t("steward.journal.exact-id")}
            inputClassName="font-mono"
          />
          {/* A reference and a number of days are exact too. */}
          <SearchField
            value={typedReference}
            onValueChange={setTypedReference}
            onSubmit={setReference}
            label={t("steward.journal.reference")}
            placeholder={t("steward.journal.exact-reference")}
            inputClassName="font-mono"
          />
          <SearchField
            value={typedDays}
            onValueChange={setTypedDays}
            onSubmit={(days) => setMinDays(/^\d+$/.test(days) ? days : "")}
            label={t("steward.journal.min-days")}
            placeholder={t("steward.journal.days-at-least")}
            inputMode="numeric"
          />
          <FilterSelect
            value={action}
            onValueChange={setAction}
            label={t("steward.journal.action")}
            every={t("steward.journal.all")}
            options={actions.map((value) => ({ value, label: actionLabel(value) }))}
          />
        </FilterBar>

        <QueryState
          query={entries}
          empty={{ title: t("steward.journal.no-entry") }}
          isEmpty={(list: JournalEntry[]) => list.length === 0}
        >
          {(list) => (
            <>
              <JournalTable entries={list} />
              <p className="text-xs text-muted-foreground">
                {list === undefined ? (
                  <SkeletonText className="w-64" />
                ) : (
                  t("steward.journal.count", { count: list.length, limit: JOURNAL_LIMIT })
                )}
              </p>
            </>
          )}
        </QueryState>
      </Panel>
    </div>
  )
}
