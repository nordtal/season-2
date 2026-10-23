import {
  ArrowSquareOutIcon,
  CalendarXIcon,
  CheckCircleIcon,
  ClockCountdownIcon,
  ProhibitIcon,
  SwapIcon,
} from "@phosphor-icons/react"
import type { Icon } from "@phosphor-icons/react"
import { TERM_ICON } from "@/components/steward/term-icons"
import { useState } from "react"
import { toast } from "sonner"

import type { Payment } from "@/lib/api"
import { count, date, euros, time } from "@/lib/format"
import { choice, t } from "@/lib/texts"
import { useNow } from "@/lib/use-now"
import { usePayments, usePeople, useSettle } from "@/lib/queries"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { Entity } from "@/components/steward/entity"
import { DataTable, type Column } from "@/components/steward/data-table"
import { FilterBar, FilterChoice, FilterMenu, FilterOption, SearchField } from "@/components/steward/filter-bar"
import { PageHeader } from "@/components/steward/page-header"
import { Panel } from "@/components/steward/panel"
import { Stat } from "@/components/steward/stat"
import { StatusBadge, type Tone } from "@/components/steward/status"
import { Empty, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"

const STATUS_ICON: Record<Payment["status"], Icon> = {
  OPEN: ClockCountdownIcon,
  PAID: CheckCircleIcon,
  EXPIRED: CalendarXIcon,
  CANCELLED: ProhibitIcon,
  SUPERSEDED: SwapIcon,
}

/**
 * Settles the one OPEN request of this row by hand. The bot books it as if bunq had reported it.
 */
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
          <TERM_ICON.settle aria-hidden />
          {t("steward.payments.settle")}
        </Button>
      }
      title={t("steward.payments.settle-ask")}
      description={t("steward.payments.settle-note", { reference })}
      action={t("steward.payments.settle")}
      act={() =>
        settle.mutateAsync(reference).then((result) => {
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
        })
      }
    />
  )
}

/** The day, and the clock under it, so the column stays narrow. */
function Moment({ at }: { at?: string }) {
  if (!at) return <>{"\u2013"}</>
  return (
    <time dateTime={at} className="flex flex-col">
      <span>{date(at)}</span>
      <span className="text-xs">{time(at)}</span>
    </time>
  )
}

const PAYMENT_TONES: Record<string, Tone> = { PAID: "ok" }

/** OPEN and past its `expires`, because the sweep has not run yet. */
function isOverdue(payment: Payment, now: number): boolean {
  return payment.status === "OPEN" && new Date(payment.expires).getTime() <= now
}

/** Payment requests as rows, with waiting ones while `payments` is undefined. */
export function PaymentsTable({
  payments,
  now,
  person = true,
}: {
  payments: Payment[] | undefined
  now: number
  person?: boolean
}) {
  const columns: Column<Payment>[] = [
    {
      // `Created` sits in the `Reference` title and `Donation` under `Amount`.
      head: t("steward.payments.reference"),
      phone: { line: 1, side: "start" },
      className: "font-mono font-medium",
      title: (payment) => t("steward.payments.created", { at: payment.created }),
      render: (payment) => payment.reference,
    },
    ...(person
      ? [
          {
            head: t("steward.payments.person"),
            grow: true,
            phone: { line: 2, side: "start" },
            waiting: (
              <div className="flex items-center gap-2">
                <Skeleton className="size-6 shrink-0 rounded-full" />
                <SkeletonText width="long" className="max-w-[6rem]" />
              </div>
            ),
            render: (payment) => <Entity id={payment.discordId} kind="discord" />,
          } satisfies Column<Payment>,
        ]
      : []),
    {
      head: t("steward.payments.days"),
      end: true,
      phone: { line: 2, side: "end", labelled: true },
      className: "tnum",
      render: (payment) => payment.days,
    },
    {
      head: t("steward.payments.amount"),
      end: true,
      phone: { line: 1, side: "end" },
      className: "tnum",
      render: (payment) => (
        <div className="flex flex-col items-end">
          <span>{euros(payment.amountCents)}</span>
          {payment.donationCents > 0 ? (
            <span className="text-xs text-muted-foreground" title={t("steward.payments.donation")}>
              +{euros(payment.donationCents)}
            </span>
          ) : null}
        </div>
      ),
    },
    {
      head: t("steward.payments.status"),
      phone: { line: 3, side: "start" },
      waiting: <Skeleton className="h-5 w-20 rounded-full" />,
      render: (payment) => {
        const late = isOverdue(payment, now)
        return (
          <div className="flex flex-wrap items-center gap-1">
            <StatusBadge tone={late ? "warn" : (PAYMENT_TONES[payment.status] ?? "idle")}>
              {t("steward.payments.state", { status: choice(payment.status) })}
            </StatusBadge>
            {late ? <StatusBadge tone="warn">{t("steward.payments.overdue")}</StatusBadge> : null}
          </div>
        )
      },
    },
    {
      head: t("steward.payments.deadline"),
      phone: { line: 4, side: "start", labelled: true },
      className: "text-muted-foreground tnum",
      render: (payment) => <Moment at={payment.expires} />,
    },
    {
      head: t("steward.payments.paid"),
      phone: { line: 4, side: "end", labelled: true },
      className: "text-muted-foreground tnum",
      render: (payment) => <Moment at={payment.settled} />,
    },
    {
      head: "",
      phone: { line: 3, side: "end" },
      render: (payment) => (
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
          {payment.status === "OPEN" ? <SettleAction reference={payment.reference} /> : null}
        </div>
      ),
    },
  ]
  return <DataTable columns={columns} rows={payments} rowKey={(payment) => payment.id} />
}

/**
 * The first three links of the chain. The sum is what the tabs asked for, because the payer can edit the amount on bunq.me.
 */
export function PaymentsPage() {
  const payments = usePayments()
  const people = usePeople()
  const [needle, setNeedle] = useState("")
  const [status, setStatus] = useState("")
  const [onlyOverdue, setOnlyOverdue] = useState(false)
  const now = useNow()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t("steward.payments.title")} />

      <QueryState
        query={payments}
        empty={{ title: t("steward.payments.no-request") }}
        isEmpty={(list: Payment[]) => list.length === 0}
      >
        {(list) => {
          /** A sum over nothing is 0, so every figure waits on `waiting`. */
          const waiting = list === undefined
          const rows = list ?? []
          const open = rows.filter((payment) => payment.status === "OPEN")
          const overdue = open.filter((payment) => isOverdue(payment, now))
          const paid = rows.filter((payment) => payment.status === "PAID")
          const requested = paid.reduce((sum, payment) => sum + payment.amountCents + payment.donationCents, 0)
          /** Built from the rows present, so a status added later still appears. */
          const present = [...new Set(rows.map((payment) => payment.status))].toSorted()
          const trimmed = needle.trim().toLowerCase()
          /** Names come from the roster, so a person is found as on the Users page. */
          const names = new Map(
            (people.data ?? []).map((one) => [one.discordId, [one.discordUsername, one.discordDisplayName]]),
          )
          const shown = rows.filter(
            (payment) =>
              (status === "" || payment.status === status) &&
              (!onlyOverdue || isOverdue(payment, now)) &&
              (trimmed === "" ||
                [payment.reference, payment.discordId, ...(names.get(payment.discordId) ?? [])].some((text) =>
                  (text ?? "").toLowerCase().includes(trimmed),
                )),
          )

          return (
            <>
              <div className="flex flex-wrap items-start gap-x-8 gap-y-4">
                <Stat
                  label={t("steward.payments.open")}
                  value={waiting ? undefined : count(open.length)}
                  hint={waiting ? undefined : t("steward.payments.past-deadline", { count: overdue.length })}
                  tone={overdue.length > 0 ? "warn" : undefined}
                />
                <Stat label={t("steward.payments.paid")} value={waiting ? undefined : count(paid.length)} />
                <Stat label={t("steward.payments.requested")} value={waiting ? undefined : euros(requested)} />
              </div>

              <Panel title={t("steward.payments.requests")}>
                <FilterBar>
                  <SearchField value={needle} onValueChange={setNeedle} label={t("steward.payments.filter")} />
                  <FilterMenu active={(status === "" ? 0 : 1) + (onlyOverdue ? 1 : 0)}>
                    <FilterOption icon={TERM_ICON.warning} pressed={onlyOverdue} onPressedChange={setOnlyOverdue}>
                      {t("steward.payments.only-overdue")}
                    </FilterOption>
                    <FilterChoice
                      label={t("steward.payments.status")}
                      value={status}
                      onValueChange={setStatus}
                      options={present.map((value) => ({
                        value,
                        label: t("steward.payments.state", { status: choice(value) }),
                        icon: STATUS_ICON[value],
                      }))}
                    />
                  </FilterMenu>
                </FilterBar>

                {!waiting && shown.length === 0 ? (
                  <Empty title={t("steward.payments.no-match")} />
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
