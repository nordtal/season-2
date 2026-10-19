import { useState } from "react"

import type { JournalAction, JournalEntry } from "@/lib/api"
import { dateTime } from "@/lib/format"
import { useJournal } from "@/lib/queries"
import { choice, message, t } from "@/lib/texts"
import { Actor, Entity } from "@/components/steward/entity"
import { DataTable, type Column } from "@/components/steward/data-table"
import { FilterBar, FilterChoice, FilterField, FilterMenu, SearchField } from "@/components/steward/filter-bar"
import { PageHeader } from "@/components/steward/page-header"
import { Panel } from "@/components/steward/panel"
import { QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"

const JOURNAL_LIMIT = 200

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

/** A long name truncates within it, so Detail keeps the room. */
const NAME_WIDTH = "max-w-40"

const WAITING_ENTITY = (
  <div className="flex items-center gap-2">
    <Skeleton className="size-6 shrink-0 rounded-full" />
    <SkeletonText width="long" className="max-w-[8rem]" />
  </div>
)

/** Journal entries as rows, with ten waiting ones while `entries` is undefined. Detail takes the room left. */
export function JournalTable({
  entries,
  concerns = true,
}: {
  entries: JournalEntry[] | undefined
  /** `false` on a person's page, where it is always that person. */
  concerns?: boolean
}) {
  const columns: Column<JournalEntry>[] = [
    {
      head: t("steward.journal.when"),
      phone: { line: 1, side: "end" },
      className: "text-muted-foreground tnum",
      title: (entry) => entry.occurred,
      render: (entry) => dateTime(entry.occurred),
    },
    {
      head: t("steward.journal.action"),
      phone: { line: 1, side: "start" },
      className: "font-medium",
      title: (entry) => entry.action,
      render: (entry) => actionLabel(entry.action),
    },
    {
      // A profile and never an id. No admin is Steward's own mark.
      head: t("steward.journal.actor"),
      phone: { line: 2, side: "start" },
      className: "text-muted-foreground",
      waiting: WAITING_ENTITY,
      render: (entry) => (
        <div className={NAME_WIDTH}>
          <Actor kind={entry.actor.kind} id={entry.actor.person ?? ""} />
        </div>
      ),
    },
    ...(concerns
      ? [
          {
            head: t("steward.journal.concerns"),
            phone: (entry: JournalEntry) => (concernsAnother(entry) ? { line: 2, side: "end" } : null),
            className: "text-muted-foreground",
            waiting: WAITING_ENTITY,
            render: (entry: JournalEntry) =>
              entry.subject || entry.mcUuid ? (
                <div className={NAME_WIDTH}>
                  {entry.subject ? <Entity id={entry.subject} /> : <Entity id={entry.mcUuid} kind="minecraft" />}
                </div>
              ) : null,
          } satisfies Column<JournalEntry>,
        ]
      : []),
    {
      head: t("steward.journal.detail"),
      grow: true,
      phone: (entry) => (restatesAction(entry) ? null : { line: 3, side: "wide" }),
      className: "text-muted-foreground",
      waiting: <SkeletonText width="full" />,
      render: (entry) => message(entry.line),
    },
  ]
  return <DataTable columns={columns} rows={entries} rowKey={(entry) => entry.id} waiting={10} />
}

/** Every part of the filter, empty, so the unfiltered query's key is the filtered one's. */
const NO_FILTER = { action: "", subject: "", reference: "", minDays: "" }

export function JournalPage() {
  const all = useJournal(NO_FILTER)
  const [action, setAction] = useState("")
  const [subject, setSubject] = useState("")
  const [typed, setTyped] = useState("")
  const [reference, setReference] = useState("")
  const [minDays, setMinDays] = useState("")
  const entries = useJournal({ action, subject, reference, minDays })
  const actions = [...new Set((all.data ?? []).map((entry) => entry.action))].toSorted()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t("steward.journal.title")} />

      <Panel title={t("steward.journal.entries")}>
        <FilterBar>
          {/* Every filter is an exact match, so the fields apply on Enter. */}
          <SearchField
            value={typed}
            onValueChange={setTyped}
            onSubmit={setSubject}
            label={t("steward.journal.subject")}
            placeholder={t("steward.journal.exact-id")}
          />
          <FilterMenu active={[action, reference, minDays].filter((value) => value !== "").length}>
            <FilterField label={t("steward.journal.reference")} value={reference} onSubmit={setReference} />
            <FilterField
              label={t("steward.journal.min-days")}
              value={minDays}
              onSubmit={(days) => setMinDays(/^\d+$/.test(days) ? days : "")}
              inputMode="numeric"
            />
            <FilterChoice
              label={t("steward.journal.action")}
              value={action}
              onValueChange={setAction}
              options={actions.map((value) => ({ value, label: actionLabel(value) }))}
            />
          </FilterMenu>
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
