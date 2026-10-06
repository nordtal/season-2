import { ArrowSquareOutIcon, HandCoinsIcon, WarningCircleIcon } from "@phosphor-icons/react"
import { useState } from "react"
import { toast } from "sonner"

import type { Payment } from "@/lib/api"
import { count, date, euros, time } from "@/lib/format"
import { choice, t } from "@/lib/texts"
import { useNow } from "@/lib/use-now"
import { usePayments, useSettle } from "@/lib/queries"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { Entity } from "@/components/steward/entity"
import { PageHeader } from "@/components/steward/page-header"
import { Panel } from "@/components/steward/panel"
import { Stat } from "@/components/steward/stat"
import { StatusBadge, type Tone } from "@/components/steward/status"
import { Empty, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
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
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={settle.isPending}
          title={t("steward.payments.settle")}
        >
          <HandCoinsIcon aria-hidden />
          {t("steward.payments.settle")}
        </Button>
      }
      title={t("steward.payments.settle-ask")}
      description={t("steward.payments.settle-note", { reference })}
      action={t("steward.payments.settle")}
      act={() => {
        settle.mutate(reference, {
          onSuccess: (result) => {
            if (result.outcome === "BOOKED") {
              toast.success(t("steward.payments.settled", { reference }), {
                description: t("steward.payments.settled-note", { days: result.days ?? 0, until: result.until ?? "" }),
              })
            } else if (result.outcome === "NOT_OPEN") {
              toast.warning(t("steward.payments.not-open", { reference }), {
                description: t("steward.payments.not-open-note", {
                  was: result.was
                    ? t("steward.payments.state", { status: choice(result.was) })
                    : t("steward.payments.state", { status: choice("PAID") }),
                }),
              })
            } else {
              toast.warning(t("steward.payments.no-payment", { reference }), {
                description: t("steward.payments.nothing-booked"),
              })
            }
          },
          onError: (error) => {
            toast.error(t("steward.payments.nothing-settled"), { description: String(error) })
          },
        })
      }}
    />
  )
}

/** A deadline or a payment's time: the day, and the clock under it, so the column stays narrow. */
function Moment({ at }: { at?: string }) {
  if (!at) return <>{"\u2013"}</>
  return (
    <time dateTime={at} className="flex flex-col">
      <span>{date(at)}</span>
      <span className="text-xs">{time(at)}</span>
    </time>
  )
}

/** Eight rows of nothing while the requests are read. */
const WAITING_PAYMENTS = [0, 1, 2, 3, 4, 5, 6, 7]

/** A request's tone; its word and what it means are the bundle's. */
const PAYMENT_TONES: Record<string, Tone> = { PAID: "ok" }

/** OPEN and past its `expires`; the sweep has just not run yet. */
function isOverdue(payment: Payment, now: number): boolean {
  return payment.status === "OPEN" && new Date(payment.expires).getTime() <= now
}

/**
 * Payment requests as rows, eight waiting ones while `payments` is undefined.
 *
 * @param person whether to draw the Person column, which a person's own page leaves out
 */
export function PaymentsTable({
  payments,
  now,
  person = true,
}: {
  payments: Payment[] | undefined
  now: number
  person?: boolean
}) {
  return (
    <Table className="steward-table">
      <TableHeader>
        <TableRow>
          {/* `Created` sits in the `Reference` title and `Donation` under `Amount`. */}
          <TableHead className="w-[7rem]">{t("steward.payments.reference")}</TableHead>
          {person ? <TableHead className="w-[11rem]">{t("steward.payments.person")}</TableHead> : null}
          <TableHead className="w-[4rem] text-right">{t("steward.payments.days")}</TableHead>
          <TableHead className="w-[7rem] text-right">{t("steward.payments.amount")}</TableHead>
          <TableHead className="w-[9rem]">{t("steward.payments.status")}</TableHead>
          <TableHead className="w-[7rem]">{t("steward.payments.deadline")}</TableHead>
          <TableHead className="w-[7rem]">{t("steward.payments.paid")}</TableHead>
          {/* `Tab` and `Settle`, which may stack. */}
          <TableHead className="w-[11rem]" />
        </TableRow>
      </TableHeader>
      <TableBody>
        {payments === undefined
          ? WAITING_PAYMENTS.map((index) => (
              <TableRow key={index}>
                <TableCell data-label={t("steward.payments.reference")}>
                  <SkeletonText width="medium" />
                </TableCell>
                {person ? (
                  <TableCell data-label={t("steward.payments.person")}>
                    <div className="flex items-center gap-2">
                      <Skeleton className="size-6 shrink-0 rounded-full" />
                      <SkeletonText width="long" className="max-w-[6rem]" />
                    </div>
                  </TableCell>
                ) : null}
                <TableCell data-label={t("steward.payments.days")} className="text-right">
                  <SkeletonText width="short" className="ml-auto" />
                </TableCell>
                <TableCell data-label={t("steward.payments.amount")} className="text-right">
                  <SkeletonText width="medium" className="ml-auto" />
                </TableCell>
                <TableCell data-label={t("steward.payments.status")}>
                  <Skeleton className="h-5 w-20 rounded-full" />
                </TableCell>
                <TableCell data-label={t("steward.payments.deadline")}>
                  <SkeletonText width="long" />
                </TableCell>
                <TableCell data-label={t("steward.payments.paid")}>
                  <SkeletonText width="long" />
                </TableCell>
                <TableCell />
              </TableRow>
            ))
          : payments.map((payment) => {
              const late = isOverdue(payment, now)
              return (
                <TableRow key={payment.id}>
                  <TableCell
                    data-label={t("steward.payments.reference")}
                    className="font-mono font-medium"
                    title={t("steward.payments.created", { at: payment.created })}
                  >
                    {payment.reference}
                  </TableCell>
                  {person ? (
                    <TableCell data-label={t("steward.payments.person")}>
                      <Entity id={payment.discordId} kind="discord" />
                    </TableCell>
                  ) : null}
                  <TableCell data-label={t("steward.payments.days")} className="text-right tnum">
                    {payment.days}
                  </TableCell>
                  <TableCell data-label={t("steward.payments.amount")} className="text-right tnum">
                    <div className="flex flex-col items-end">
                      <span>{euros(payment.amountCents)}</span>
                      {payment.donationCents > 0 ? (
                        <span className="text-xs text-muted-foreground" title={t("steward.payments.donation")}>
                          +{euros(payment.donationCents)}
                        </span>
                      ) : null}
                    </div>
                  </TableCell>
                  <TableCell data-label={t("steward.payments.status")}>
                    <div className="flex flex-wrap items-center gap-1">
                      <StatusBadge
                        tone={late ? "warn" : (PAYMENT_TONES[payment.status] ?? "idle")}
                        tipContent={t("steward.payments.state-tip", { status: choice(payment.status) })}
                      >
                        {t("steward.payments.state", { status: choice(payment.status) })}
                      </StatusBadge>
                      {late ? (
                        <StatusBadge tone="warn" tipContent={t("steward.payments.overdue-tip")}>
                          {t("steward.payments.overdue")}
                        </StatusBadge>
                      ) : null}
                    </div>
                  </TableCell>
                  <TableCell data-label={t("steward.payments.deadline")} className="text-muted-foreground tnum">
                    <Moment at={payment.expires} />
                  </TableCell>
                  <TableCell data-label={t("steward.payments.paid")} className="text-muted-foreground tnum">
                    <Moment at={payment.settled} />
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center justify-end gap-1">
                      {payment.shareUrl ? (
                        <Button asChild variant="ghost" size="sm">
                          <a href={payment.shareUrl} target="_blank" rel="noreferrer" title={payment.shareUrl}>
                            <ArrowSquareOutIcon aria-hidden />
                            {t("steward.payments.tab")}
                          </a>
                        </Button>
                      ) : (
                        <span className="text-xs text-muted-foreground" title={t("steward.payments.no-tab")}>
                          {"\u2013"}
                        </span>
                      )}
                      {/* Only an OPEN request can be settled. */}
                      {payment.status === "OPEN" ? <SettleAction reference={payment.reference} /> : null}
                    </div>
                  </TableCell>
                </TableRow>
              )
            })}
      </TableBody>
    </Table>
  )
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
      <PageHeader title={t("steward.payments.title")} />

      <QueryState
        query={payments}
        empty={{ title: t("steward.payments.no-request"), note: t("steward.payments.no-request-note") }}
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
              <div className="flex flex-wrap items-start gap-8">
                <Stat
                  label={t("steward.payments.open")}
                  value={waiting ? undefined : count(open.length)}
                  hint={waiting ? undefined : t("steward.payments.past-deadline", { count: overdue.length })}
                  tone={overdue.length > 0 ? "warn" : undefined}
                />
                <Stat label={t("steward.payments.paid")} value={waiting ? undefined : count(paid.length)} />
                <Separator orientation="vertical" className="h-14" />
                <Stat
                  label={t("steward.payments.requested")}
                  value={waiting ? undefined : euros(requested)}
                  hint={t("steward.payments.requested-hint")}
                />
                <p className="max-w-prose text-xs text-muted-foreground">{t("steward.payments.not-the-balance")}</p>
              </div>

              <Panel title={t("steward.payments.requests")}>
                <div className="flex flex-wrap items-center gap-2">
                  <Label htmlFor="payment-status" className="text-muted-foreground">
                    {t("steward.payments.status")}
                  </Label>
                  <Select
                    value={status === "" ? "ALL" : status}
                    onValueChange={(value) => setStatus(value === "ALL" ? "" : value)}
                  >
                    <SelectTrigger id="payment-status" className="w-full sm:w-56">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="ALL">{t("steward.payments.all")}</SelectItem>
                      {present.map((value) => (
                        <SelectItem key={value} value={value}>
                          {t("steward.payments.state", { status: choice(value) })}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  {overdue.length > 0 ? (
                    <span className="flex items-center gap-2 text-xs text-warning">
                      <WarningCircleIcon className="size-4 shrink-0" aria-hidden />
                      {t("steward.payments.overdue-note", { count: overdue.length })}
                    </span>
                  ) : null}
                </div>

                {!waiting && shown.length === 0 ? (
                  <Empty
                    title={t("steward.payments.none-with-status")}
                    note={t("steward.payments.none-with-status-note")}
                  />
                ) : (
                  <PaymentsTable payments={waiting ? undefined : shown} now={now} />
                )}
              </Panel>
            </>
          )
        }}
      </QueryState>
    </div>
  )
}
