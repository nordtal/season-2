import { useState } from "react"

import type { JournalAction, JournalEntry } from "@/lib/api"
import { dateTime } from "@/lib/format"
import { useJournal } from "@/lib/queries"
import { choice, message, t } from "@/lib/texts"
import { actionIcon } from "@/components/steward/actions"
import { Actor, Entity } from "@/components/steward/entity"
import { DataTable, type Column } from "@/components/steward/data-table"
import { FilterBar, FilterChoice, FilterField, FilterMenu } from "@/components/steward/filter-bar"
import { PageHeader } from "@/components/steward/page-header"
import { Panel } from "@/components/steward/panel"
import { QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { ReferencePicker } from "@/components/steward/reference-picker"
import { TERM_ICON } from "@/components/steward/term-icons"

const JOURNAL_LIMIT = 200

/**
 * The audit log. Every filter is an exact match, so actions are picked from the rows present and the person from the people.
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

const PERSON = { to: "DISCORD_USER", optional: true } as const

export function JournalPage() {
  const all = useJournal(NO_FILTER)
  const [action, setAction] = useState("")
  const [subject, setSubject] = useState("")
  const [reference, setReference] = useState("")
  const [minDays, setMinDays] = useState("")
  const entries = useJournal({ action, subject, reference, minDays })
  const actions = [...new Set((all.data ?? []).map((entry) => entry.action))].toSorted()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t("steward.journal.title")} />

      <Panel title={t("steward.journal.entries")}>
        <FilterBar>
          <div className="flex min-w-[11rem] flex-1 basis-0">
            <label htmlFor="journal-subject" className="sr-only">
              {t("steward.journal.subject")}
            </label>
            <ReferencePicker
              id="journal-subject"
              label={t("steward.journal.subject")}
              reference={PERSON}
              values={subject === "" ? [] : [subject]}
              multi={false}
              disabled={false}
              typed={null}
              face={
                subject === "" ? (
                  <span className="flex items-center gap-2 text-muted-foreground">
                    <TERM_ICON.person aria-hidden />
                    {t("steward.journal.subject")}
                  </span>
                ) : undefined
              }
              onChange={(picked) => setSubject(picked[0] ?? "")}
            />
          </div>
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
              options={actions.map((value) => ({ value, label: actionLabel(value), icon: actionIcon(value) }))}
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
