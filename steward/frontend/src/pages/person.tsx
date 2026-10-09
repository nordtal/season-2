import { useParams } from "@tanstack/react-router"

import type { Grant, Person } from "@/lib/api"
import { dateTime } from "@/lib/format"
import { choice, span, t } from "@/lib/texts"
import { useNow } from "@/lib/use-now"
import { useGrants, useJournal, usePeople, usePersonPayments } from "@/lib/queries"
import { Entity } from "@/components/steward/entity"
import { PageHeader } from "@/components/steward/page-header"
import { Panel } from "@/components/steward/panel"
import { QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Stat } from "@/components/steward/stat"
import { StatusBadge, type Tone } from "@/components/steward/status"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { AccessBadge, LinkBadge, MemberBadge, PersonActions, RoleBadges, personName } from "@/pages/access-person"
import { JournalTable } from "@/pages/journal"
import { PaymentsTable } from "@/pages/payments"

/**
 * One person: who they are, what they may, and everything Steward knows them by, on an address of their own.
 *
 * Sessions are left out, since nothing records a login per person yet; the journal shows the writes.
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

/** The page once the roster names the person. */
function PersonView({ person, people }: { person: Person; people: readonly Person[] }) {
  // One clock for the whole page, so that the badges and the tables cannot disagree about "now".
  const now = useNow()
  const payments = usePersonPayments(person.discordId)
  const journal = useJournal("", person.discordId)

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

/** The figures under the name; without a person each keeps its place with a bar. */
function PersonStats({ person, people, now }: { person?: Person; people?: readonly Person[]; now?: number }) {
  const badge = "text-base font-normal"
  return (
    <div className="grid grid-cols-2 gap-x-4 gap-y-5 sm:grid-cols-3 xl:grid-cols-6">
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
      <Stat
        label={t("steward.people.language")}
        value={person?.locale}
        hint={person ? t("steward.people.last-changed", { at: person.updated }) : undefined}
      />
    </div>
  )
}

/** Three rows of nothing while a person's periods are read. */
const WAITING_GRANTS = [0, 1, 2]

/** Where a period stands right now, judged from the row itself rather than from the roster. */
function grantTone(grant: Grant, now: number): { label: string; tone: Tone; title: string } {
  if (grant.revoked) {
    return {
      label: t("steward.people.revoked", { at: grant.revoked }),
      tone: "down",
      title: t("steward.people.revoked-tip"),
    }
  }
  const from = new Date(grant.validFrom).getTime()
  const until = new Date(grant.validUntil).getTime()
  if (from > now) {
    return {
      label: t("steward.people.begins", { at: grant.validFrom }),
      tone: "idle",
      title: t("steward.people.begins-tip"),
    }
  }
  if (until > now) {
    return { label: t("steward.people.running"), tone: "ok", title: t("steward.people.running-tip") }
  }
  return { label: t("steward.people.over"), tone: "idle", title: t("steward.people.over-tip") }
}

/** A uuid or a request id, short enough for a cell and complete in the title attribute. */
function shortId(value: string): string {
  return value.length > 8 ? `${value.slice(0, 8)}…` : value
}

/** One person's chain, period by period, on their page; the writes are the page's actions. */
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
          <Table className="steward-table">
            <TableHeader>
              <TableRow>
                <TableHead className="w-[7rem]">{t("steward.people.source-column")}</TableHead>
                <TableHead>{t("steward.people.window")}</TableHead>
                <TableHead className="w-[13rem]">{t("steward.people.state")}</TableHead>
                <TableHead className="w-[8rem]">{t("steward.people.request")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list === undefined
                ? WAITING_GRANTS.map((index) => (
                    <TableRow key={index}>
                      <TableCell data-label={t("steward.people.source-column")}>
                        <SkeletonText width="medium" />
                      </TableCell>
                      <TableCell data-label={t("steward.people.window")}>
                        <SkeletonText width="long" />
                      </TableCell>
                      <TableCell data-label={t("steward.people.state")}>
                        <Skeleton className="h-5 w-24 rounded-full" />
                      </TableCell>
                      <TableCell data-label={t("steward.people.request")}>
                        <SkeletonText width="short" />
                      </TableCell>
                    </TableRow>
                  ))
                : list.map((row) => {
                    const state = grantTone(row, now)
                    return (
                      <TableRow key={row.id}>
                        <TableCell data-label={t("steward.people.source-column")}>
                          {t("steward.people.source", { source: choice(row.source) }) || row.source}
                        </TableCell>
                        <TableCell data-label={t("steward.people.window")} className="text-muted-foreground tnum">
                          {dateTime(row.validFrom)}
                          {" \u2013 "}
                          {dateTime(row.validUntil)}
                        </TableCell>
                        <TableCell data-label={t("steward.people.state")}>
                          <StatusBadge tone={state.tone} tipContent={state.title}>
                            {state.label}
                          </StatusBadge>
                        </TableCell>
                        <TableCell data-label={t("steward.people.request")}>
                          {row.paymentRequestId ? (
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
                          )}
                        </TableCell>
                      </TableRow>
                    )
                  })}
            </TableBody>
          </Table>
        )}
      </QueryState>
    </>
  )
}
