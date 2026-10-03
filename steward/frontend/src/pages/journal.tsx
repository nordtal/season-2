import { MagnifyingGlassIcon } from "@phosphor-icons/react"
import { useState } from "react"

import type { JournalEntry } from "@/lib/api"
import { count, dateTime } from "@/lib/format"
import { useJournal } from "@/lib/queries"
import { choice, message, t } from "@/lib/texts"
import { Actor, Entity } from "@/components/steward/entity"
import { PageHeader } from "@/components/steward/page-header"
import { QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

/** Ten rows of nothing while the record is read. */
const WAITING_ENTRIES = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]

/**
 * The audit log.
 *
 * Both filters are exact matches, so actions are picked from the rows present and the subject is submitted.
 */
/** An action as the admin bundle names it; one no longer listed reads as its own name. */
function actionLabel(action: string): string {
  return t("journal.action", { action: choice(action) })
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
                            <TableCell data-label="Action" className="font-medium" title={entry.action}>
                              {actionLabel(entry.action)}
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
                              ) : null}
                            </TableCell>
                            {/* The line, rendered from its key and typed values, wrapping where TableCell would not. */}
                            <TableCell data-label="Detail" className="text-muted-foreground whitespace-normal">
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
