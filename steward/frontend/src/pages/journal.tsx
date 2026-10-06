import { MagnifyingGlassIcon } from "@phosphor-icons/react"
import { useState } from "react"

import type { JournalEntry } from "@/lib/api"
import { dateTime } from "@/lib/format"
import { useJournal } from "@/lib/queries"
import { humanise } from "@/lib/settings-tree"
import { choice, message, t } from "@/lib/texts"
import { Actor, Entity } from "@/components/steward/entity"
import { PageHeader } from "@/components/steward/page-header"
import { Panel } from "@/components/steward/panel"
import { QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

/** How many entries the journal route hands out unless asked for more. */
const JOURNAL_LIMIT = 200

/** Ten rows of nothing while the record is read. */
const WAITING_ENTRIES = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]

/**
 * The audit log.
 *
 * Both filters are exact matches, so actions are picked from the rows present and the subject is submitted.
 */
/** An action as the admin bundle names it; one it does not list, from an older row, reads as its words. */
function actionLabel(action: string): string {
  const key = choice(action)
  const named = t("journal.action", { action: key })
  return named === key ? humanise(key) : named
}

/** A line with no values says nothing its action does not, so the phone card leaves it out. */
function restatesAction(entry: JournalEntry): boolean {
  return Object.keys(entry.line.args ?? {}).length === 0
}

/** Whether the entry concerns someone other than the one who acted, which alone earns a line on a phone. */
function concernsAnother(entry: JournalEntry): boolean {
  if (entry.subject) return entry.subject !== entry.actor.person
  return entry.mcUuid !== undefined
}

export function JournalPage() {
  const columns = {
    when: t("steward.journal.when"),
    action: t("steward.journal.action"),
    actor: t("steward.journal.actor"),
    concerns: t("steward.journal.concerns"),
    detail: t("steward.journal.detail"),
  }
  /** The unfiltered query, for the options; it shares its key with the filtered one while no filter is set. */
  const all = useJournal("", "")
  const [action, setAction] = useState("")
  const [subject, setSubject] = useState("")
  const [typed, setTyped] = useState("")
  const entries = useJournal(action, subject)
  const actions = [...new Set((all.data ?? []).map((entry) => entry.action))].toSorted()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t("steward.journal.title")} />

      <Panel title={t("steward.journal.entries")}>
        <div className="flex flex-wrap items-end gap-4">
          <div className="flex w-full min-w-0 flex-col gap-1.5 sm:w-auto">
            <Label htmlFor="journal-action">{t("steward.journal.action")}</Label>
            <Select
              value={action === "" ? "ALL" : action}
              onValueChange={(value) => setAction(value === "ALL" ? "" : value)}
            >
              <SelectTrigger id="journal-action" className="w-full sm:w-64">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">{t("steward.journal.all")}</SelectItem>
                {actions.map((value) => (
                  <SelectItem key={value} value={value}>
                    {actionLabel(value)}
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
              <Label htmlFor="journal-subject">{t("steward.journal.subject")}</Label>
              <Input
                id="journal-subject"
                value={typed}
                onChange={(event) => setTyped(event.target.value)}
                placeholder={t("steward.journal.exact-id")}
                className="w-full font-mono sm:w-64"
                autoComplete="off"
                spellCheck={false}
              />
            </div>
            <Button type="submit" variant="outline">
              <MagnifyingGlassIcon aria-hidden />
              {t("steward.journal.filter")}
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
                {t("steward.journal.reset")}
              </Button>
            ) : null}
          </form>
        </div>

        <QueryState
          query={entries}
          empty={{ title: t("steward.journal.no-entry") }}
          isEmpty={(list: JournalEntry[]) => list.length === 0}
        >
          {(list) => (
            <>
              <Table className="steward-table">
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-[13rem]">{columns.when}</TableHead>
                    <TableHead className="w-[12rem]">{columns.action}</TableHead>
                    <TableHead className="w-[16rem]">{columns.actor}</TableHead>
                    <TableHead className="w-[14rem]">{columns.concerns}</TableHead>
                    <TableHead>{columns.detail}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {list === undefined
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
                          <TableCell data-label={columns.concerns}>
                            <div className="flex items-center gap-2">
                              <Skeleton className="size-6 shrink-0 rounded-full" />
                              <SkeletonText width="long" className="max-w-[7rem]" />
                            </div>
                          </TableCell>
                          <TableCell data-label={columns.detail}>
                            <SkeletonText width="full" />
                          </TableCell>
                        </TableRow>
                      ))
                    : list.map((entry) => (
                        <TableRow key={entry.id}>
                          {/* No `data-label`: on a phone the time and the action head the card as one line. */}
                          <TableCell title={entry.occurred}>
                            <span className="flex flex-wrap items-baseline gap-x-3">
                              <span className="text-muted-foreground tnum">{dateTime(entry.occurred)}</span>
                              <span className="font-medium md:hidden">{actionLabel(entry.action)}</span>
                            </span>
                          </TableCell>
                          <TableCell data-phone="off" className="font-medium" title={entry.action}>
                            {actionLabel(entry.action)}
                          </TableCell>
                          {/* A profile, never an id; no admin at all is Steward's own mark. */}
                          <TableCell data-label={columns.actor} className="text-muted-foreground">
                            <Actor kind={entry.actor.kind} id={entry.actor.person ?? ""} />
                          </TableCell>
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
                          {/* The line, rendered from its key and typed values, wrapping where TableCell would not. */}
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
