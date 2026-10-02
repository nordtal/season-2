import { ArrowSquareOutIcon, HandCoinsIcon, WarningCircleIcon } from "@phosphor-icons/react"
import { useState } from "react"
import { toast } from "sonner"

import type { Payment } from "@/lib/api"
import { count, dateTime, euros } from "@/lib/format"
import { useNow } from "@/lib/use-now"
import { usePayments, useSettle } from "@/lib/queries"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { Entity } from "@/components/steward/entity"
import { PageHeader } from "@/components/steward/page-header"
import { Stat } from "@/components/steward/stat"
import { StatusBadge, type Tone } from "@/components/steward/status"
import { Empty, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Separator } from "@/components/ui/separator"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

/** Settles the one OPEN request of this row by hand; the bot books it as if bunq had reported it. */
function SettleAction({ reference }: { reference: string }) {
  const settle = useSettle()
  return (
    <AskThenAct
      trigger={
        <Button type="button" variant="outline" size="sm" disabled={settle.isPending}>
          <HandCoinsIcon aria-hidden />
          Settle
        </Button>
      }
      title="Settle?"
      description={
        <>
          Marks {reference} paid by hand and writes the access period it bought. Use this only once the money has
          actually arrived - it books access, it does not check bunq.
        </>
      }
      action="Settle"
      act={() => {
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
    />
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
