import { useParams } from "@tanstack/react-router"

import type { Grant, Person } from "@/lib/api"
import { count, dateTime } from "@/lib/format"
import { choice, span, t } from "@/lib/texts"
import { useNow } from "@/lib/use-now"
import { useGrants, useJournal, usePeople, usePersonPayments } from "@/lib/queries"
import { Entity } from "@/components/steward/entity"
import { PageHeader } from "@/components/steward/page-header"
import { Panel } from "@/components/steward/panel"
import { QueryState, Skeleton } from "@/components/steward/query-state"
import { Stat } from "@/components/steward/stat"
import { StatusBadge, type Tone } from "@/components/steward/status"
import { DataTable } from "@/components/steward/data-table"
import { AccessBadge, LinkBadge, MemberBadge, PersonActions, RoleBadges, personName } from "@/pages/access-person"
import { JournalTable } from "@/pages/journal"
import { PaymentsTable } from "@/pages/payments"

/**
 * One person: who they are, what they may do, and everything Steward knows them by. Sessions are left out, because nothing records a login per person yet.
 */
export function PersonPage() {
  const { id } = useParams({ from: "/access/$id" })
  const people = usePeople()
  const person = people.data?.find((candidate) => candidate.discordId === id)

  return (
    <QueryState query={people} isEmpty={() => !person} empty={{ title: t("steward.people.unknown-person") }}>
      {(list) =>
        list && person ? (
          <PersonView person={person} people={list} />
        ) : (
          <div className="flex flex-col gap-6">
            <PageHeader title={id} />
            <PersonStats />
          </div>
        )
      }
    </QueryState>
  )
}

function PersonView({ person, people }: { person: Person; people: readonly Person[] }) {
  // One clock for the page, so the badges and tables cannot disagree about "now".
  const now = useNow()
  const payments = usePersonPayments(person.discordId)
  const journal = useJournal({ subject: person.discordId })

  return (
    <div className="flex flex-col gap-8">
      <PageHeader title={personName(person)} actions={<PersonActions person={person} />} />

      <PersonStats person={person} people={people} now={now} />

      <Panel title={t("steward.people.periods")}>
        <PersonPeriods person={person} now={now} />
      </Panel>

      <Panel title={t("steward.payments.title")}>
        <QueryState
          query={payments}
          empty={{ title: t("steward.people.no-payment") }}
          isEmpty={(list) => list.length === 0}
        >
          {(list) => <PaymentsTable payments={list} now={now} person={false} />}
        </QueryState>
      </Panel>

      <Panel title={t("steward.journal.title")}>
        <QueryState
          query={journal}
          empty={{ title: t("steward.people.no-entry") }}
          isEmpty={(list) => list.length === 0}
        >
          {(list) => <JournalTable entries={list} concerns={false} />}
        </QueryState>
      </Panel>
    </div>
  )
}

/** Without a person, each figure keeps its place with a bar. */
function PersonStats({ person, people, now }: { person?: Person; people?: readonly Person[]; now?: number }) {
  const badge = "text-base font-normal"
  return (
    <div className="grid grid-cols-2 gap-x-4 gap-y-5 sm:grid-cols-4">
      <Stat
        label={t("steward.people.guild")}
        value={person ? <Entity id={person.discordId} kind="discord" /> : undefined}
        hint={person ? <MemberBadge state={person.memberState} /> : undefined}
        valueClassName={badge}
      />
      <Stat
        label={t("steward.people.access")}
        value={person && now !== undefined ? <AccessBadge person={person} now={now} /> : undefined}
        valueClassName={badge}
      />
      <Stat
        label={t("steward.people.minecraft")}
        value={
          person ? (
            person.minecraftUuid ? (
              <Entity id={person.minecraftUuid} kind="minecraft" />
            ) : (
              <LinkBadge person={person} />
            )
          ) : undefined
        }
        hint={person?.linked ? t("steward.people.linked-at", { at: person.linked }) : undefined}
        valueClassName={badge}
      />
      <Stat
        label={t("steward.people.roles")}
        value={person && people ? <RoleBadges person={person} people={people} /> : undefined}
        valueClassName={badge}
      />
      <Stat label={t("steward.people.playtime")} value={person ? span(person.playtimeSeconds, "minutes") : undefined} />
      <Stat label={t("steward.people.aura")} value={person ? count(person.aura) : undefined} />
      <Stat label={t("steward.people.spins")} value={person ? count(person.spins) : undefined} />
      <Stat
        label={t("steward.people.language")}
        value={person?.locale}
        hint={person ? t("steward.people.last-changed", { at: person.updated }) : undefined}
      />
    </div>
  )
}

/** Returns where a period stands now, judged from the row and not from the roster. */
function grantTone(grant: Grant, now: number): { label: string; tone: Tone } {
  if (grant.revoked) return { label: t("steward.people.revoked", { at: grant.revoked }), tone: "down" }
  if (new Date(grant.validFrom).getTime() > now) {
    return { label: t("steward.people.begins", { at: grant.validFrom }), tone: "idle" }
  }
  if (new Date(grant.validUntil).getTime() > now) return { label: t("steward.people.running"), tone: "ok" }
  return { label: t("steward.people.over"), tone: "idle" }
}

/** Returns an id short enough for a cell and complete in the title. */
function shortId(value: string): string {
  return value.length > 8 ? `${value.slice(0, 8)}…` : value
}

function PersonPeriods({ person, now }: { person: Person; now: number }) {
  const grants = useGrants(person.discordId)

  return (
    <>
      <QueryState
        query={grants}
        empty={{ title: t("steward.people.no-period") }}
        isEmpty={(list: Grant[]) => list.length === 0}
      >
        {(list) => (
          <DataTable
            rows={list}
            waiting={3}
            rowKey={(row) => row.id}
            columns={[
              {
                head: t("steward.people.source-column"),
                phone: { line: 1, side: "start" },
                render: (row) => t("steward.people.source", { source: choice(row.source) }) || row.source,
              },
              {
                head: t("steward.people.window"),
                grow: true,
                phone: { line: 2, side: "wide" },
                className: "text-muted-foreground tnum",
                render: (row) => (
                  <>
                    {dateTime(row.validFrom)}
                    {" \u2013 "}
                    {dateTime(row.validUntil)}
                  </>
                ),
              },
              {
                head: t("steward.people.state"),
                phone: { line: 1, side: "end" },
                waiting: <Skeleton className="h-5 w-24 rounded-full" />,
                render: (row) => {
                  const state = grantTone(row, now)
                  return <StatusBadge tone={state.tone}>{state.label}</StatusBadge>
                },
              },
              {
                head: t("steward.people.request"),
                phone: { line: 3, side: "start", labelled: true },
                render: (row) =>
                  row.paymentRequestId ? (
                    <span className="font-mono text-xs" title={row.paymentRequestId}>
                      {shortId(row.paymentRequestId)}
                    </span>
                  ) : (
                    <span
                      className="text-xs text-muted-foreground"
                      title={t("steward.people.request-gone", { source: choice(row.source) })}
                    >
                      {"\u2013"}
                    </span>
                  ),
              },
            ]}
          />
        )}
      </QueryState>
    </>
  )
}
