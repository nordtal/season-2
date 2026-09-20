import {
  ArrowSquareOutIcon,
  ClockCounterClockwiseIcon,
  HandCoinsIcon,
  HourglassIcon,
  LinkBreakIcon,
  MagnifyingGlassIcon,
  ShieldCheckIcon,
  ShieldSlashIcon,
  UserPlusIcon,
  WarningCircleIcon,
  WarningIcon,
} from "@phosphor-icons/react"
import type { ReactNode } from "react"
import { useState } from "react"
import { toast } from "sonner"

import type { Grant, JournalEntry, Payment, Person } from "@/lib/api"
import { count, date, dateTime, euros, playtime, relative, splitPlaytime } from "@/lib/format"
import {
  useAvatarBaseUrl,
  useCommands,
  useGrantAccess,
  useGrants,
  useJournal,
  useMe,
  usePayments,
  usePeople,
  useRevokeAccess,
  useSetPlaytime,
} from "@/lib/queries"
import { MinecraftFace, PersonIdentity, personLabel } from "@/components/steward/identity"
import { InlineCommandAction } from "@/components/steward/inline-command"
import { PageHeader } from "@/components/steward/page-header"
import { RowActions, type RowAction } from "@/components/steward/row-actions"
import { Stat } from "@/components/steward/stat"
import { StatusBadge, type Tone } from "@/components/steward/status"
import { Empty, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import {
  ResponsiveAlertDialog,
  ResponsiveAlertDialogAction,
  ResponsiveAlertDialogCancel,
  ResponsiveAlertDialogContent,
  ResponsiveAlertDialogDescription,
  ResponsiveAlertDialogFooter,
  ResponsiveAlertDialogHeader,
  ResponsiveAlertDialogTitle,
  ResponsiveAlertDialogTrigger,
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogDescription,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Separator } from "@/components/ui/separator"
import { Switch } from "@/components/ui/switch"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"

/**
 * The four pages of the access half (concept §10a.7): who may join, what they paid, which accounts
 * are one person, and what an admin did about it.
 *
 * They are one file because they are one chain, read left to right: **request → tab → paid →
 * access → linked**. Payments is the first three links, Access the fourth, Accounts the fifth,
 * and Journal is the record of every hand that reached into any of them. Splitting them into four
 * files would put the vocabulary that has to agree - what "active" means, what a revoked period
 * looks like - in four places.
 *
 * **Nothing here is computed that the database does not answer.** Where a number would be a guess
 * the page says so in a sentence instead of printing it: the amount of a paid request is what the
 * tab asked for and not what arrived, and a person whose access is inactive while their latest
 * period still runs may have been revoked *or* may be waiting for the SMP to open. Both are written
 * out below rather than quietly rounded into a badge.
 */

// --- the vocabulary the four pages share ----------------------------------------------------------

/**
 * Guild membership.
 *
 * `MEMBER` is deliberately the quiet one. Green would spend a status colour on the ordinary case
 * and leave nothing louder for the two cases an admin is actually scanning for - and a ban does not
 * stop a paid period running down, it only refuses the login right now.
 */
const MEMBER_STATES: Record<string, { label: string; tone: Tone; title: string }> = {
  MEMBER: { label: "Member", tone: "idle", title: "In the guild, as the bot last saw it." },
  LEFT: {
    label: "left",
    tone: "warn",
    title:
      "No longer in the guild. A purchased period keeps running regardless - it is not paused.",
  },
  BANNED: {
    label: "banned",
    tone: "down",
    title:
      "Banned in Discord. The login is refused while that holds; the paid period keeps expiring meanwhile.",
  },
}

function MemberBadge({ state }: { state: string }) {
  const known = MEMBER_STATES[state]
  // An unknown value is shown, not swallowed: `member_state` has a CHECK constraint today, and a
  // page that printed nothing for a value added tomorrow would look empty rather than new.
  if (!known) {
    return (
      <StatusBadge tone="idle" tipContent="This interface does not know this membership state.">
        {state}
      </StatusBadge>
    )
  }
  return (
    <StatusBadge tone={known.tone} tipContent={known.title}>
      {known.label}
    </StatusBadge>
  )
}

/**
 * Access, from the two fields that are deliberately not one.
 *
 * `accessActive` is the login decision - a revoked grant never counts, not even inside its own
 * window. `accessUntil` is the end of the latest period *on record*, revoked ones included. Keeping
 * both is what makes "their access was taken away" visible at all: without the date, somebody
 * whose access was taken away would look exactly like somebody who never bought any.
 *
 * The third case - inactive, but the latest period still lies in the future - has two possible
 * causes and this badge does not pretend to know which: revoked, or bought before the SMP opened
 * and therefore not yet started. Both are named in the tooltip, and the per-person dialog answers
 * it for certain, because a grant carries its own `revoked`.
 */
function AccessBadge({ person, now }: { person: Person; now: number }) {
  const until = person.accessUntil ? new Date(person.accessUntil).getTime() : null

  if (person.accessActive) {
    return (
      <StatusBadge
        tone="ok"
        tipContent={`An unrevoked period covers right now, until ${dateTime(person.accessUntil)}.`}
      >
        active until {date(person.accessUntil)}
      </StatusBadge>
    )
  }
  if (until === null) {
    return (
      <StatusBadge tone="idle" tipContent="No period has ever been written for this account.">
        never
      </StatusBadge>
    )
  }
  if (until > now) {
    return (
      <StatusBadge
        tone="down"
        tipContent={
          "No valid period covers now, although the latest one runs on paper until " +
          dateTime(person.accessUntil) +
          ". That means either revoked - or bought before the SMP opened, and therefore not yet begun. Which of the two is shown in this person's periods."
        }
      >
        no access
      </StatusBadge>
    )
  }
  return (
    <StatusBadge tone="idle" tipContent="The latest period has expired.">
      expired {relative(person.accessUntil, now)}
    </StatusBadge>
  )
}

/**
 * The fifth link of the chain, for the one state that has no face to draw.
 *
 * Paid but unlinked is the one combination worth a warning colour: that person has spent money and
 * still cannot join, because the proxy knows Minecraft accounts and not Discord ones. The linked
 * case never reaches this component any more (steward/46) - the table draws `MinecraftFace`
 * directly for it, name and head, so this badge only has one state left to speak for.
 */
function LinkBadge({ person }: { person: Person }) {
  return (
    <StatusBadge
      tone={person.accessActive ? "warn" : "idle"}
      tipContent={
        person.accessActive
          ? "Access paid for, but no Minecraft account linked - this person cannot reach the server until they type the code from the login screen into Discord."
          : "No Minecraft account linked."
      }
    >
      not linked
    </StatusBadge>
  )
}

/** Three rows of nothing while a person's periods are read. Most accounts have one or two. */
const WAITING_GRANTS = [0, 1, 2]

const GRANT_SOURCES: Record<string, string> = {
  PURCHASE: "Purchase",
  ADMIN: "by hand",
}

/**
 * A person a row names by identifier alone - a payment request, a journal entry.
 *
 * steward/45 put identifiers in one place, behind a click. These two tables were the reason that
 * promise did not hold: they carry a Discord id or a Minecraft uuid and nothing else, so drawing
 * the row meant drawing the number. The roster already knows who that is, so it is looked up here
 * instead - and when it does not (a payment from somebody who has since left the guild, a journal
 * entry about an account nobody linked), `PersonIdentity` falls back to saying so, and the number
 * is still one click away in its popover, next to the copy button.
 *
 * The lookup is a linear scan of a list the page has already fetched. The roster is a few hundred
 * rows and this runs per visible row of one page of twenty; an index would be a second thing to
 * keep in step with the first.
 */
function PersonByIdentifier({
  discordId,
  mcUuid,
  people,
  avatarBaseUrl,
}: {
  discordId?: string
  mcUuid?: string
  people: Person[] | undefined
  avatarBaseUrl: string | undefined
}) {
  const known = people?.find(
    (candidate) =>
      (discordId !== undefined && candidate.discordId === discordId) ||
      (mcUuid !== undefined && candidate.minecraftUuid === mcUuid),
  )
  return (
    <PersonIdentity
      discordId={known?.discordId ?? discordId ?? ""}
      discordUsername={known?.discordUsername}
      discordDisplayName={known?.discordDisplayName}
      discordAvatarUrl={known?.discordAvatarUrl}
      mcUuid={mcUuid ?? known?.minecraftUuid}
      mcName={known?.mcName}
      avatarBaseUrl={avatarBaseUrl}
    />
  )
}

/** Where a period stands right now, judged from the row itself rather than from the roster. */
function grantTone(grant: Grant, now: number): { label: string; tone: Tone; title: string } {
  if (grant.revoked) {
    return {
      label: `revoked ${dateTime(grant.revoked)}`,
      tone: "down",
      title: "A revoked period never counts, not even inside its own window.",
    }
  }
  const from = new Date(grant.validFrom).getTime()
  const until = new Date(grant.validUntil).getTime()
  if (from > now) {
    return {
      label: `begins ${relative(grant.validFrom, now)}`,
      tone: "idle",
      title: "Bought but not yet begun - a period is appended, never overwritten.",
    }
  }
  if (until > now) {
    return { label: "running", tone: "ok", title: "This period covers right now." }
  }
  return { label: "expired", tone: "idle", title: "This period lies entirely behind us." }
}

/** A uuid or a request id, short enough for a cell and complete in the title attribute. */
function shortId(value: string): string {
  return value.length > 8 ? `${value.slice(0, 8)}…` : value
}

/** Rows per page of the People table (steward/46) - the whole roster is filtered first, always. */
const PEOPLE_PAGE_SIZE = 20

// --- 1. /access ---------------------------------------------------------------------------------

/**
 * The roster's frame - the header row and, with it, the six column widths.
 *
 * It is a component rather than markup inside the table because it is drawn twice: once around the
 * real rows and once around the waiting ones (steward/120). Two copies of six `w-[Nrem]` classes
 * is one copy that drifts, and the drift is visible - every heading would shift sideways the
 * moment the roster lands.
 */
function PeopleTable({ children }: { children: ReactNode }) {
  return (
    <Table className="steward-table">
      <TableHeader>
        <TableRow>
          <TableHead className="w-[16rem]">Person</TableHead>
          <TableHead className="w-[20rem]">
            <AccessColumnHead />
          </TableHead>
          <TableHead className="w-[9rem]">Minecraft</TableHead>
          <TableHead className="w-[9rem]">Roles</TableHead>
          {/* steward/119: the prestige tier is derived from this number and stored nowhere, so it
              is the only thing an admin can look at - and, through the row action beside it, the
              only thing they can move. */}
          <TableHead className="w-[7rem]">Playtime</TableHead>
          <TableHead className="w-[15rem]" />
        </TableRow>
      </TableHeader>
      <TableBody>{children}</TableBody>
    </Table>
  )
}

/** How many rows are drawn before the roster is there. A page of the real thing is twenty. */
const WAITING_PEOPLE = [0, 1, 2, 3, 4, 5, 6, 7]

/**
 * One person before there is one: the avatar, the name, the access badge and the rest, in the
 * shapes the real row puts in those cells. The row action stays empty - a menu with nothing behind
 * it is worse than a gap.
 */
function WaitingPersonRow() {
  return (
    <TableRow>
      {/*
        The widths aim at the column, not at a plausible name. `steward-table` is `table-layout:
        auto`, so a cell narrower than its `w-[Nrem]` lets the column collapse below it and every
        heading to its right slides - which is the jump this whole ticket is against. Drawn at the
        declared width the waiting table IS the declared layout; a name longer than 16rem still
        nudges it when it lands, and that is a property of the real table, not of this row.
      */}
      <TableCell data-label="Person" className="font-medium">
        <div className="flex items-center gap-2">
          <Skeleton className="size-6 shrink-0 rounded-full" />
          <SkeletonText width="full" className="max-w-[13rem]" />
        </div>
      </TableCell>
      <TableCell data-label="Access">
        <Skeleton className="h-5 w-[15rem] max-w-full rounded-full" />
      </TableCell>
      <TableCell data-label="Minecraft">
        <div className="flex items-center gap-2">
          <Skeleton className="size-5 shrink-0 rounded-sm" />
          <SkeletonText width="full" className="max-w-[6.5rem]" />
        </div>
      </TableCell>
      <TableCell data-label="Roles">
        <Skeleton className="h-5 w-[7rem] max-w-full rounded-full" />
      </TableCell>
      <TableCell data-label="Playtime">
        <SkeletonText width="medium" className="min-w-[3rem]" />
      </TableCell>
      <TableCell />
    </TableRow>
  )
}

/**
 * The roster: everyone the bot knows, and what they may.
 *
 * The two writes on this page are the reason it needs a paragraph of its own. Granting and revoking
 * used to be `/access` in Discord and nothing else; since 2026-09-13 this is a second door into the
 * same room, and the price of a second door is that "who let them in" has to stay
 * answerable. It does, because every click here writes an `audit_log` row naming the admin - which
 * is exactly what the Journal page shows.
 */
export function AccessPage() {
  const people = usePeople()
  const avatarBase = useAvatarBaseUrl()
  const commands = useCommands()
  const unlinkCommand = commands.data?.find((command) => command.name === "/access unlink")
  const [needle, setNeedle] = useState("")
  const [onlyWithAccess, setOnlyWithAccess] = useState(false)
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<Person | null>(null)
  // The person whose access is being revoked. Separate from `selected` on purpose: opening this
  // one closes the other, so there is never a dialog inside a dialog.
  const [revoking, setRevoking] = useState<Person | null>(null)
  // And the same for the other two writes, for a second reason on top of that one (steward/106):
  // a row's actions live in a popover once there are more than two of them, and a Radix popover
  // closes on any interaction outside itself - which a dialog's overlay is. A dialog rendered
  // inside the popover is therefore unmounted by the click that opened it. So every dialog on this
  // page is rendered here, beside the table, and the row holds nothing but buttons.
  const [unlinking, setUnlinking] = useState<Person | null>(null)
  const [granting, setGranting] = useState<Person | null>(null)
  // Not `playtime`: that name is the formatter this page draws the column with.
  const [playtimeFor, setPlaytimeFor] = useState<Person | null>(null)
  // One clock for the whole render, so that two badges in one row cannot disagree about "now".
  const now = Date.now()

  // A filter or the switch changing the result set is exactly when a page number from before it
  // stops meaning anything - kept on the table itself rather than clamped only where it is read,
  // so a stale "page 3" never flashes before the roster shrinks under it.
  function changeNeedle(value: string) {
    setNeedle(value)
    setPage(0)
  }
  function changeOnlyWithAccess(value: boolean) {
    setOnlyWithAccess(value)
    setPage(0)
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Access"
        actions={<GrantDialog />}
      />


      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium">People</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {/*
            steward/103, measured at 390px on 2026-09-17. This row was `flex flex-wrap` with the
            field `w-full flex-1`: `flex-1` sets `flex-basis: 0%`, which beats `w-full`, so the two
            never wrapped onto separate lines - they shared one line about 100px too narrow for
            them. The field could not shrink out of the way either, because an `<input>` has an
            intrinsic minimum width and the `min-w-0` that would have released it sat on the
            wrapper rather than on the input itself. So the switch was pushed against the card edge
            and the placeholder was cut at `Filter by name`.

            Two lines below `sm`, one above, and `min-w-0` on the input where it belongs.
          */}
          <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-center sm:gap-4">
            <div className="flex w-full min-w-0 items-center gap-2 sm:min-w-64 sm:flex-1">
              <MagnifyingGlassIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
              <Input
                value={needle}
                onChange={(event) => changeNeedle(event.target.value)}
                // Short enough to be readable at 390px rather than cut mid-word. The long sentence
                // it replaces is now the accessible name, where nothing clips it.
                placeholder="Filter by name or id"
                aria-label="Filter people by name, Discord id, or Minecraft account"
                className="min-w-0"
                autoComplete="off"
              />
            </div>
            <div className="flex min-w-0 items-center gap-2">
              <Switch
                id="only-with-access"
                checked={onlyWithAccess}
                onCheckedChange={changeOnlyWithAccess}
              />
              <Label htmlFor="only-with-access">with access only</Label>
            </div>
          </div>

          <QueryState
            query={people}
            empty={{
              title: "Nobody yet",
              note: "The bot has not seen a single Discord account yet - or it is not running.",
            }}
            isEmpty={(list: Person[]) => list.length === 0}
          >
            {(list) => {
              if (list === undefined) {
                // The search field, the filter and the six column headings above are all on screen
                // already - they are written into the page, not fetched. What is missing is eight
                // rows, so eight rows is what is drawn (steward/120).
                return (
                  <>
                    <PeopleTable>
                      {WAITING_PEOPLE.map((index) => (
                        <WaitingPersonRow key={index} />
                      ))}
                    </PeopleTable>
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <SkeletonText className="w-44 text-xs" />
                    </div>
                  </>
                )
              }
              const trimmed = needle.trim().toLowerCase()
              // Four things, per steward/46, even though it reads as five fields: a Discord name
              // (guild nickname or username - whichever this account has), a Minecraft name, and
              // both ids. The ids stay searchable although this table no longer draws them (see
              // `identity.tsx`) - somebody holding an id out of a log has to be able to find the
              // person behind it.
              const rows = list.filter(
                (person) =>
                  (!onlyWithAccess || person.accessActive) &&
                  (trimmed === "" ||
                    person.discordId.toLowerCase().includes(trimmed) ||
                    (person.discordUsername ?? "").toLowerCase().includes(trimmed) ||
                    (person.discordDisplayName ?? "").toLowerCase().includes(trimmed) ||
                    (person.mcName ?? "").toLowerCase().includes(trimmed) ||
                    (person.minecraftUuid ?? "").toLowerCase().includes(trimmed)),
              )
              if (rows.length === 0) {
                return (
                  <Empty
                    title="Nobody matches"
                    note={
                      onlyWithAccess
                        ? "With this filter and \"with access only\" nobody is left."
                        : "No loaded account contains this string in its name or either id."
                    }
                  />
                )
              }
              // Paged AFTER filtering, over the whole roster - steward/46's explicit worry is a
              // search that only reaches the visible page, which would look like it works right up
              // until the 21st match.
              const pageCount = Math.max(1, Math.ceil(rows.length / PEOPLE_PAGE_SIZE))
              const clampedPage = Math.min(page, pageCount - 1)
              const paged = rows.slice(
                clampedPage * PEOPLE_PAGE_SIZE,
                clampedPage * PEOPLE_PAGE_SIZE + PEOPLE_PAGE_SIZE,
              )
              return (
                <>
                  <PeopleTable>
                      {paged.map((person) => (
                        <TableRow key={person.discordId}>
                          <TableCell data-label="Person" className="font-medium">
                            <div className="flex flex-wrap items-center gap-2">
                              <PersonIdentity
                                discordId={person.discordId}
                                discordUsername={person.discordUsername}
                                discordDisplayName={person.discordDisplayName}
                                discordAvatarUrl={person.discordAvatarUrl}
                                mcUuid={person.minecraftUuid}
                                mcName={person.mcName}
                                avatarBaseUrl={avatarBase.data}
                              />
                              {/* "Member" is the ordinary case and is left unsaid (steward/46) -
                               * LEFT and BANNED are exactly the two states worth a glance, and
                               * they still get one, right next to the name rather than in a
                               * column of their own. */}
                              {person.memberState !== "MEMBER" ? (
                                <MemberBadge state={person.memberState} />
                              ) : null}
                            </div>
                          </TableCell>
                          <TableCell data-label="Access">
                            <AccessBadge person={person} now={now} />
                          </TableCell>
                          <TableCell data-label="Minecraft">
                            {person.minecraftUuid ? (
                              <MinecraftFace
                                mcUuid={person.minecraftUuid}
                                mcName={person.mcName}
                                avatarBaseUrl={avatarBase.data}
                              />
                            ) : (
                              <LinkBadge person={person} />
                            )}
                          </TableCell>
                          <TableCell data-label="Roles">
                            <div className="flex items-center gap-1">
                              {person.donor ? (
                                <StatusBadge
                                  tone="idle"
                                  tipContent="Given once, never taken away - which is why handing the role out in Discord is harmless."
                                >
                                  Supporter
                                </StatusBadge>
                              ) : null}
                              {person.admin ? (
                                <StatusBadge
                                  tone="idle"
                                  tipContent="Mirrors the Discord admin role. If the role goes, this mark goes with it."
                                >
                                  Admin
                                </StatusBadge>
                              ) : null}
                              {!person.donor && !person.admin ? (
                                <span className="text-xs text-muted-foreground">–</span>
                              ) : null}
                            </div>
                          </TableCell>
                          <TableCell data-label="Playtime">
                            {/* `playtime` and not `duration` (steward/126): this column answers the
                                dialog beside it, and that one asks in days, hours and minutes. It
                                draws the dash for null by itself, which is the answer for somebody
                                who has never been online - not "0 min". */}
                            <span className="text-sm tabular-nums">
                              {playtime(person.playtimeSeconds ?? undefined)}
                            </span>
                          </TableCell>
                          <TableCell>
                            <RowActions
                              label={`Actions for ${personName(person)}`}
                              actions={rowActions(person, {
                                unlinkable: unlinkCommand !== undefined,
                                onPeriods: () => setSelected(person),
                                onGrant: () => setGranting(person),
                                onPlaytime: () => setPlaytimeFor(person),
                                onRevoke: () => setRevoking(person),
                                onUnlink: () => setUnlinking(person),
                              })}
                            />
                          </TableCell>
                        </TableRow>
                      ))}
                  </PeopleTable>
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <p className="text-xs text-muted-foreground">
                      {count(rows.length)} of {count(list.length)} loaded accounts.
                    </p>
                    {pageCount > 1 ? (
                      <div className="flex items-center gap-2">
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          disabled={clampedPage === 0}
                          onClick={() => setPage((current) => Math.max(0, current - 1))}
                        >
                          Previous
                        </Button>
                        <span className="text-xs text-muted-foreground tnum">
                          Page {clampedPage + 1} of {pageCount}
                        </span>
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          disabled={clampedPage >= pageCount - 1}
                          onClick={() => setPage((current) => current + 1)}
                        >
                          Next
                        </Button>
                      </div>
                    ) : null}
                  </div>
                </>
              )
            }}
          </QueryState>
        </CardContent>
      </Card>

      <ResponsiveDialog open={selected !== null} onOpenChange={(open) => (open ? null : setSelected(null))}>
        <ResponsiveDialogContent className="max-w-2xl">
          {selected ? (
            <PersonGrants
              person={selected}
              now={now}
              onRevoke={() => {
                const person = selected
                setSelected(null)
                setRevoking(person)
              }}
            />
          ) : null}
        </ResponsiveDialogContent>
      </ResponsiveDialog>

      {revoking ? (
        <RevokeDialog
          person={revoking}
          open
          onOpenChange={(open) => (open ? null : setRevoking(null))}
        />
      ) : null}

      {/* Both rendered here rather than in the row, for the reason `unlinking` is declared with. */}
      {unlinking ? (
        <InlineCommandAction
          command={unlinkCommand}
          argumentName="member"
          value={unlinking.discordId}
          label="Unlink"
          icon={LinkBreakIcon}
          destructive
          confirmDescription="Breaks the link between this Discord account and its Minecraft account. The paid period is untouched; the person can link a Minecraft account again afterwards."
          open
          onOpenChange={(open) => (open ? null : setUnlinking(null))}
        />
      ) : null}

      {granting ? (
        <GrantDialog
          person={granting}
          open
          onOpenChange={(open) => (open ? null : setGranting(null))}
        />
      ) : null}

      {playtimeFor ? (
        <PlaytimeDialog
          person={playtimeFor}
          open
          onOpenChange={(open) => (open ? null : setPlaytimeFor(null))}
        />
      ) : null}
    </div>
  )
}

/** What to call somebody in a control's accessible name, in the order the table itself reads. */
function personName(person: Person): string {
  return person.discordDisplayName ?? person.discordUsername ?? person.mcName ?? person.discordId
}

/**
 * WHICH ACTIONS ONE ROW OFFERS - the whole of steward/47's second finding, in one function.
 *
 * Till looked at this table on a phone on 2026-09-17 and found "Unlink" and "Periods" drawn
 * against every person alike, whatever their state; he asked for actions and information per person
 * to be shown dynamically instead. So every entry below is conditional on something this row
 * actually knows, and the conditions are the point:
 *
 * - **Periods** only when a period exists. `accessUntil` is the end of the latest period *on
 *   record*, revoked ones included, so `null` means the dialog would open on nothing at all.
 * - **Grant** always. There is no state in which more access cannot be given - that is exactly
 *   what the header's own button does, and this one arrives with the person already filled in.
 * - **Revoke** only while access is active. No greyed-out button for somebody without any: there
 *   is nothing to take away, and a disabled destructive control reads as "not allowed" rather than
 *   "not applicable".
 * - **Unlink** only when a Minecraft account is linked - the bug Till found. It was drawn for
 *   everybody, including the people with nothing to unlink. It is still additionally conditional
 *   on `/access unlink` being declared for `Surface.WEB` in `/api/commands`, which is steward/47's
 *   original rule and unchanged: a withdrawn declaration hides the button rather than producing a
 *   404.
 *
 * <h2>The two of the five that are deliberately not here</h2>
 * steward/106 names five `access` commands for this table. `settle` is not one of these rows'
 * business: it books one payment reference, not one person, and steward/47 already moved it to the
 * Payments page where the row names the reference. `status` is the row itself plus the Periods
 * dialog - both read the same tables directly - and it is declared `CONSOLE` only, so a command
 * button for it could not be drawn even if it were wanted.
 */
function rowActions(
  person: Person,
  on: {
    unlinkable: boolean
    onPeriods: () => void
    onGrant: () => void
    onPlaytime: () => void
    onRevoke: () => void
    onUnlink: () => void
  },
): RowAction[] {
  const actions: RowAction[] = []

  if (person.accessUntil) {
    actions.push({
      key: "periods",
      node: (
        <Button type="button" variant="ghost" size="sm" onClick={on.onPeriods}>
          <ClockCounterClockwiseIcon aria-hidden />
          Periods
        </Button>
      ),
    })
  }

  actions.push({
    key: "grant",
    node: (
      <Button type="button" variant="ghost" size="sm" onClick={on.onGrant}>
        <UserPlusIcon aria-hidden />
        Grant
      </Button>
    ),
  })

  actions.push({
    key: "playtime",
    node: (
      <Button type="button" variant="ghost" size="sm" onClick={on.onPlaytime}>
        <HourglassIcon aria-hidden />
        Playtime
      </Button>
    ),
  })

  if (person.accessActive) {
    actions.push({
      key: "revoke",
      node: (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="text-destructive"
          onClick={on.onRevoke}
        >
          <ShieldSlashIcon aria-hidden />
          Revoke
        </Button>
      ),
    })
  }

  if (person.minecraftUuid && on.unlinkable) {
    actions.push({
      key: "unlink",
      node: (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="text-destructive"
          onClick={on.onUnlink}
        >
          <LinkBreakIcon aria-hidden />
          Unlink
        </Button>
      ),
    })
  }

  return actions
}

/*
 * WHY THIS PAGE MAY WRITE AT ALL, which used to be a paragraph at the top of it: `/access` in
 * Discord is the first door into the same tables and this is the second. The price of a second
 * door is paid in the record - every grant and every revocation from here writes a row into the
 * journal naming the admin who clicked. That is a decision, and a decision belongs here and not on
 * the screen of somebody who has already opened the page.
 */

/** The header of the access column, with the reason its two halves disagree. */
function AccessColumnHead() {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span tabIndex={0} className="underline decoration-dotted underline-offset-4">
          Access
        </span>
      </TooltipTrigger>
      <TooltipContent className="max-w-xs">
        Two readings, deliberately not one: whether an unrevoked period covers right now - and when
        the latest period ends, revoked ones included. Without the second, somebody whose access was
        taken away would look exactly like somebody who never had any.
      </TooltipContent>
    </Tooltip>
  )
}

/**
 * Granting, with the arithmetic named out loud.
 *
 * The rules are the database's, not this form's: a day is exactly 24 hours, a new period is
 * appended behind a running one instead of replacing it, and a purchase made before the SMP opens
 * starts on the opening day. Writing them here is the only way the person clicking can predict what
 * the row will say afterwards.
 */
function GrantDialog({
  person,
  open,
  onOpenChange,
}: {
  /** Prefills the id, for the row-level "Grant" of steward/106. */
  person?: Person
  /** When given, the dialog is controlled from outside and draws no trigger of its own. */
  open?: boolean
  onOpenChange?: (open: boolean) => void
} = {}) {
  const grant = useGrantAccess()
  const [discordId, setDiscordId] = useState(person?.discordId ?? "")
  const [days, setDays] = useState("30")
  const parsedDays = Number.parseInt(days, 10)
  const usable = discordId.trim().length > 0 && Number.isFinite(parsedDays) && parsedDays > 0

  return (
    <ResponsiveAlertDialog open={open} onOpenChange={onOpenChange}>
      {open === undefined ? (
        <ResponsiveAlertDialogTrigger asChild>
          <Button type="button">
            <UserPlusIcon aria-hidden />
            Grant access
          </Button>
        </ResponsiveAlertDialogTrigger>
      ) : null}
      <ResponsiveAlertDialogContent>
        <ResponsiveAlertDialogHeader>
          <ResponsiveAlertDialogTitle>Grant access by hand</ResponsiveAlertDialogTitle>
          <ResponsiveAlertDialogDescription>
            Writes a period with the source <code className="text-xs">ADMIN</code> - no payment, no
            bunq tab. The person may then join the server as soon as their Minecraft account is
            linked.
          </ResponsiveAlertDialogDescription>
        </ResponsiveAlertDialogHeader>

        <div className="flex flex-col gap-3">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="grant-discord-id">Discord-ID</Label>
            <Input
              id="grant-discord-id"
              value={discordId}
              onChange={(event) => setDiscordId(event.target.value)}
              placeholder="e.g. 214906139328839681"
              className="font-mono"
              autoComplete="off"
              spellCheck={false}
              inputMode="numeric"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="grant-days">Days</Label>
            <Input
              id="grant-days"
              value={days}
              onChange={(event) => setDays(event.target.value)}
              type="number"
              min={1}
              className="w-32"
            />
          </div>
          <ul className="flex list-disc flex-col gap-1 pl-4 text-sm text-muted-foreground">
            <li>A day is exactly 24 hours, not a calendar day.</li>
            <li>
              If a period is already running, the new one is appended - paid time is never lost,
              and periods are never summed across a gap.
            </li>
            <li>
              If the SMP launch has not been reached, the period starts at that date and not
              today.
            </li>
            <li>
              If the bot does not know this Discord id yet, the account is created for it. A
              mistyped id therefore produces a person who does not exist - and no error.
            </li>
          </ul>
        </div>

        <ResponsiveAlertDialogFooter>
          <ResponsiveAlertDialogCancel>Cancel</ResponsiveAlertDialogCancel>
          <ResponsiveAlertDialogAction
            disabled={!usable || grant.isPending}
            onClick={() => {
              grant.mutate(
                { discordId: discordId.trim(), days: parsedDays },
                {
                  onSuccess: (written: Grant) => {
                    // The person if this dialog was opened from their row, the id they typed if
                    // it was opened from the toolbar - there is nobody else to name then, and an
                    // echo of what was typed is what confirms the right account was hit.
                    toast.success(
                      `Access granted for ${person ? personLabel(person) : written.discordId}`,
                      {
                        description: `Valid ${dateTime(written.validFrom)} until ${dateTime(
                          written.validUntil,
                        )}. A journal line names you.`,
                      },
                    )
                    setDiscordId(person?.discordId ?? "")
                  },
                  onError: (error) => {
                    toast.error("No access was granted", { description: String(error) })
                  },
                },
              )
            }}
          >
            Grant
          </ResponsiveAlertDialogAction>
        </ResponsiveAlertDialogFooter>
      </ResponsiveAlertDialogContent>
    </ResponsiveAlertDialog>
  )
}

/**
 * Sets an account's total play time by hand (steward/119).
 *
 * <h2>Why this page may write that number at all</h2>
 * The prestige tier is not a column. `Prestige.java` derives it from total play time every time it
 * draws a name, which is the right design and leaves exactly one lever: the seconds themselves.
 * Till asked for this so that two accounts of different tiers can stand beside each other without
 * anybody waiting out the hours first (season-2-ingame/23).
 *
 * <h2>Three fields here, seconds on the wire (steward/126)</h2>
 * Nobody types 32400, and nobody thinks in 37.5 either - that was this dialog until 2026-09-20, and
 * the trouble with it was not that it was hard to compute but that a slip was invisible: 37.5 typed
 * as 375 is a plausible number of hours. Days, hours and minutes cannot be mistyped that way, and
 * the multiplication happens here so that the request body and the `player_playtime.seconds` column
 * agree on a unit.
 *
 * <h2>An empty field is a zero, and nothing is out of range</h2>
 * Hours over 23 and minutes over 59 are CARRIED rather than refused: somebody who types
 * "0 days 50 hours" means two days and two hours and has not made a mistake. The only thing refused
 * is a negative number, because there is nothing it could mean.
 *
 * <h2>What it does not do</h2>
 * Stop the proxy. Somebody online right now keeps accumulating on top of whatever this writes,
 * because the flush adds seconds; there is no lock and there is deliberately no second column
 * saying "this one was set by hand". One number, one truth.
 */
function PlaytimeDialog({
  person,
  open,
  onOpenChange,
}: {
  person: Person
  open?: boolean
  onOpenChange?: (open: boolean) => void
}) {
  const write = useSetPlaytime()
  const start = splitPlaytime(person.playtimeSeconds ?? 0)
  const [days, setDays] = useState(String(start.days))
  const [hours, setHours] = useState(String(start.hours))
  const [minutes, setMinutes] = useState(String(start.minutes))

  // An empty field is a zero and not an error, which is `Number("")` and not a branch: clearing a
  // field to type into it is the ordinary way to use three number inputs, and a form that went
  // unusable in between would be unusable most of the time somebody is typing in it. Anything that
  // is not a number at all is still NaN, and `usable` below is what catches it.
  const field = (value: string) => Number(value.replace(",", "."))
  const parts = [field(days), field(hours), field(minutes)]
  const usable = parts.every((part) => Number.isFinite(part) && part >= 0)
  const seconds = usable
    ? Math.round(parts[0] * 86_400 + parts[1] * 3_600 + parts[2] * 60)
    : 0

  return (
    <ResponsiveAlertDialog open={open} onOpenChange={onOpenChange}>
      <ResponsiveAlertDialogContent>
        <ResponsiveAlertDialogHeader>
          <ResponsiveAlertDialogTitle>Set play time</ResponsiveAlertDialogTitle>
          <ResponsiveAlertDialogDescription>
            Replaces the counted total for {personName(person)}. The prestige tier follows from it,
            and there is nothing else to set.
          </ResponsiveAlertDialogDescription>
        </ResponsiveAlertDialogHeader>

        <div className="flex flex-col gap-1.5">
          {/* Three fields in one row: on a phone they are still three columns rather than a stack,
              because a day, an hour and a minute are one number read left to right. */}
          <div className="grid grid-cols-3 gap-2">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="playtime-days">Days</Label>
              <Input
                id="playtime-days"
                value={days}
                onChange={(event) => setDays(event.target.value)}
                type="number"
                min={0}
                inputMode="numeric"
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="playtime-hours">Hours</Label>
              <Input
                id="playtime-hours"
                value={hours}
                onChange={(event) => setHours(event.target.value)}
                type="number"
                min={0}
                inputMode="numeric"
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="playtime-minutes">Minutes</Label>
              <Input
                id="playtime-minutes"
                value={minutes}
                onChange={(event) => setMinutes(event.target.value)}
                type="number"
                min={0}
                inputMode="numeric"
              />
            </div>
          </div>
          <p className="text-xs text-muted-foreground">
            Counted so far: {playtime(person.playtimeSeconds ?? undefined)}
            {usable ? `, becoming ${playtime(seconds)}` : null}. Anybody online while this is
            written keeps counting up from the new value.
          </p>
        </div>

        <ResponsiveAlertDialogFooter>
          <ResponsiveAlertDialogCancel>Cancel</ResponsiveAlertDialogCancel>
          <ResponsiveAlertDialogAction
            disabled={!usable || write.isPending}
            onClick={() => {
              write.mutate(
                { discordId: person.discordId, seconds },
                {
                  onSuccess: () => {
                    toast.success(`Play time set for ${personName(person)}`, {
                      description: `${playtime(seconds)} from now on. A journal line names you.`,
                    })
                  },
                  onError: (error) => {
                    toast.error("The play time was not written", { description: String(error) })
                  },
                },
              )
            }}
          >
            Save
          </ResponsiveAlertDialogAction>
        </ResponsiveAlertDialogFooter>
      </ResponsiveAlertDialogContent>
    </ResponsiveAlertDialog>
  )
}

/**
 * Revoking - the destructive half.
 *
 * It takes the whole remaining run and not one period: that is what the backend's single statement
 * does, and it is what lets the login path get away with one `max(valid_until)`. Saying "every
 * running period" here is therefore accurate and not a simplification.
 */
/**
 * Revoking, from the table row or from the opened person.
 *
 * Till asked for both doors (2026-09-13). They are not nested: the button inside the person dialog
 * CLOSES that dialog and opens this one at page level, because an ResponsiveAlertDialog inside an open
 * ResponsiveDialog is two focus traps on one screen, and which of them gets the keyboard back afterwards is
 * not something anybody here can verify without a browser.
 *
 * @param open when given, the dialog is controlled from outside and draws no trigger of its own
 */
function RevokeDialog({
  person,
  open,
  onOpenChange,
}: {
  person: Person
  open?: boolean
  onOpenChange?: (open: boolean) => void
}) {
  const revoke = useRevokeAccess()

  return (
    <ResponsiveAlertDialog open={open} onOpenChange={onOpenChange}>
      {open === undefined ? (
        <ResponsiveAlertDialogTrigger asChild>
          <Button type="button" variant="ghost" size="sm" className="text-destructive">
            <ShieldSlashIcon aria-hidden />
            Revoke
          </Button>
        </ResponsiveAlertDialogTrigger>
      ) : null}
      <ResponsiveAlertDialogContent>
        <ResponsiveAlertDialogHeader>
          <ResponsiveAlertDialogTitle>Revoke access?</ResponsiveAlertDialogTitle>
          <ResponsiveAlertDialogDescription>
            What is revoked is the <span className="text-foreground">whole remaining run</span> of
            the person this row names - every period not yet expired at once, not a single one.
          </ResponsiveAlertDialogDescription>
        </ResponsiveAlertDialogHeader>

        <div className="flex flex-col gap-3 text-sm">
          <p className="flex items-start gap-2 rounded-md border border-warning/30 bg-warning/8 px-3 py-2 text-warning">
            <WarningIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
            Anyone playing right now is thrown out: the proxy re-checks every connected player's
            access regularly and disconnects as soon as it no longer holds - not only at the next
            login.
          </p>
          <p className="text-muted-foreground">
            Paid time does not come back this way. A later grant starts fresh and does not credit
            the revoked remainder.
          </p>
          <p className="text-muted-foreground">
            The entry stays and is only marked revoked - which is why a date still stands beside
            "no access" in the list, instead of the person looking like a stranger.
          </p>
        </div>

        <ResponsiveAlertDialogFooter>
          <ResponsiveAlertDialogCancel>Cancel</ResponsiveAlertDialogCancel>
          <ResponsiveAlertDialogAction
            variant="destructive"
            disabled={revoke.isPending}
            onClick={() => {
              revoke.mutate(person.discordId, {
                onSuccess: (result) => {
                  // Zero is a real answer and not a success: between opening this dialog and
                  // clicking, the run may have ended or somebody else may have revoked it.
                  if (result.revoked === 0) {
                    toast.warning("There was nothing to revoke", {
                      description: `No period was still running for ${personLabel(person)}.`,
                    })
                    return
                  }
                  toast.success(
                    `${count(result.revoked)} period(s) of ${personLabel(person)} revoked`,
                    { description: "A journal line names you." },
                  )
                },
                onError: (error) => {
                  toast.error("Nothing was revoked", { description: String(error) })
                },
              })
            }}
          >
            Revoke
          </ResponsiveAlertDialogAction>
        </ResponsiveAlertDialogFooter>
      </ResponsiveAlertDialogContent>
    </ResponsiveAlertDialog>
  )
}

/** One person's chain, period by period. Read-only: the two writes stand in the table row. */
function PersonGrants({
  person,
  now,
  onRevoke,
}: {
  person: Person
  now: number
  onRevoke: () => void
}) {
  const grants = useGrants(person.discordId)
  const avatarBase = useAvatarBaseUrl()

  return (
    <>
      <ResponsiveDialogHeader>
        <ResponsiveDialogTitle className="flex flex-wrap items-center justify-between gap-3 pr-6">
          <PersonIdentity
            discordId={person.discordId}
            discordUsername={person.discordUsername}
            discordDisplayName={person.discordDisplayName}
            discordAvatarUrl={person.discordAvatarUrl}
            mcUuid={person.minecraftUuid}
            mcName={person.mcName}
            avatarBaseUrl={avatarBase.data}
          />
          {person.accessActive ? (
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="text-destructive"
              onClick={onRevoke}
            >
              <ShieldSlashIcon aria-hidden />
              Revoke
            </Button>
          ) : null}
        </ResponsiveDialogTitle>
        <ResponsiveDialogDescription>
          Request → tab → paid → access → linked. This is the fourth link: every period, its source
          and - for a purchase - the payment request it came from.
        </ResponsiveDialogDescription>
      </ResponsiveDialogHeader>

      <div className="flex flex-wrap gap-6">
        <Stat label="Guild" value={<MemberBadge state={person.memberState} />} />
        <Stat
          label="Minecraft"
          value={
            person.minecraftUuid ? (
              <MinecraftFace
                mcUuid={person.minecraftUuid}
                mcName={person.mcName}
                avatarBaseUrl={avatarBase.data}
              />
            ) : (
              "–"
            )
          }
          hint={person.linked ? `linked ${dateTime(person.linked)}` : "not linked"}
        />
        <Stat
          label="Language"
          value={person.locale}
          hint={`last changed ${relative(person.updated, now)}`}
        />
      </div>

      <Separator />

      <QueryState
        query={grants}
        empty={{
          title: "No period",
          note: "None has ever been written for this account - neither bought nor by hand.",
        }}
        isEmpty={(list: Grant[]) => list.length === 0}
      >
        {(list) => (
          <Table className="steward-table">
            <TableHeader>
              <TableRow>
                <TableHead className="w-[7rem]">Source</TableHead>
                <TableHead>Window</TableHead>
                <TableHead className="w-[13rem]">State</TableHead>
                <TableHead className="w-[8rem]">Request</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list === undefined
                ? WAITING_GRANTS.map((index) => (
                    <TableRow key={index}>
                      <TableCell data-label="Source">
                        <SkeletonText width="medium" />
                      </TableCell>
                      <TableCell data-label="Window">
                        <SkeletonText width="long" />
                      </TableCell>
                      <TableCell data-label="State">
                        <Skeleton className="h-5 w-24 rounded-full" />
                      </TableCell>
                      <TableCell data-label="Request">
                        <SkeletonText width="short" />
                      </TableCell>
                    </TableRow>
                  ))
                : list.map((row) => {
              const state = grantTone(row, now)
              return (
                <TableRow key={row.id}>
                  <TableCell data-label="Source">{GRANT_SOURCES[row.source] ?? row.source}</TableCell>
                  <TableCell data-label="Window" className="text-muted-foreground tnum">
                    {dateTime(row.validFrom)} – {dateTime(row.validUntil)}
                  </TableCell>
                  <TableCell data-label="State">
                    <StatusBadge tone={state.tone} tipContent={state.title}>
                      {state.label}
                    </StatusBadge>
                  </TableCell>
                  <TableCell data-label="Request">
                    {row.paymentRequestId ? (
                      <span className="font-mono text-xs" title={row.paymentRequestId}>
                        {shortId(row.paymentRequestId)}
                      </span>
                    ) : (
                      <span
                        className="text-xs text-muted-foreground"
                        title={
                          row.source === "PURCHASE"
                            ? "Bought, but the payment request is no longer in the database - it is set to NULL when the request is deleted."
                            : "Granted by hand, so there is no payment request."
                        }
                      >
                        –
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

// --- 2. /payments --------------------------------------------------------------------------------

/** Eight rows of nothing while the requests are read - a page of the real table is longer. */
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

/** OPEN and past its `expires`: nobody is about to pay this, the sweep has just not run yet. */
function isOverdue(payment: Payment, now: number): boolean {
  return payment.status === "OPEN" && new Date(payment.expires).getTime() <= now
}

/**
 * The payments: the first three links of the chain.
 *
 * The sum at the top is the one number on these four pages that could mislead, so it is labelled
 * rather than printed bare: `amount_cents` is what the tab **asked for**, and the payer can edit the
 * amount on the bunq.me page. What actually arrived is not in this table at all.
 */
export function PaymentsPage() {
  const payments = usePayments()
  // The roster is fetched here only to put a name on a payment's Discord id (steward/45). It is
  // the same cached query the People page uses, so on a session that has visited that page this
  // costs nothing, and a failure to load it is not a failure of this page: the identity falls
  // back to "no Discord name on record" and the row still shows its reference and its amount.
  const people = usePeople()
  const avatarBase = useAvatarBaseUrl()
  const commands = useCommands()
  const settleCommand = commands.data?.find((command) => command.name === "/access settle")
  const [status, setStatus] = useState("")
  const now = Date.now()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Payments"
      />

      <QueryState
        query={payments}
        empty={{
          title: "No payment request",
          note: "Nobody has requested access yet - or the bot is not running.",
        }}
        isEmpty={(list: Payment[]) => list.length === 0}
      >
        {(list) => {
          // Everything below reads `list ?? []` and then asks `waiting` before it prints a
          // figure (steward/120). A sum over nothing is 0, and "0 paid" is not a waiting state -
          // it is a wrong answer that will be silently corrected a moment later.
          const waiting = list === undefined
          const rows = list ?? []
          const open = rows.filter((payment) => payment.status === "OPEN")
          const overdue = open.filter((payment) => isOverdue(payment, now))
          const paid = rows.filter((payment) => payment.status === "PAID")
          const requested = paid.reduce(
            (sum, payment) => sum + payment.amountCents + payment.donationCents,
            0,
          )
          // Built from what is here, plus the value being filtered on, so that a status added to
          // the CHECK constraint later still appears the moment one row carries it.
          const present = [...new Set(rows.map((payment) => payment.status))].sort()
          const shown = rows.filter((payment) => status === "" || payment.status === status)

          return (
            <>
              <Card>
                <CardContent className="flex flex-wrap items-start gap-8 pt-6">
                  <Stat
                    label="Open"
                    value={waiting ? undefined : count(open.length)}
                    hint={
                      waiting ? undefined : `${count(overdue.length)} of them past the deadline`
                    }
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
                    This is <span className="text-foreground">not the balance</span>: on the
                    bunq.me page the paying person can change the amount, and what actually arrived
                    is in none of these columns. This interface does not ask bunq - the bot does.
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
                        {count(overdue.length)} open request(s) are past their deadline - nobody is
                        going to pay those, they are only waiting for the bot's cleanup run.
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
                          {/*
                            steward/114: these ten headers summed to 95rem in a 1152px (72rem)
                            card. `Created` is folded into a title on `Reference` (still there,
                            one hover away, the same pattern `PersonGrants`'s `Request` cell
                            already uses for a full id) and `Donation` is folded into `Amount` as
                            a second line - both are real data kept, not data dropped, which is
                            the "fewer columns" the ticket's exit clause asks for once shrinking
                            widths alone cannot close a 23rem gap on fields that do not wrap.
                          */}
                          <TableHead className="w-[7rem]">Reference</TableHead>
                          <TableHead className="w-[11rem]">Person</TableHead>
                          <TableHead className="w-[4rem] text-right">Days</TableHead>
                          <TableHead className="w-[7rem] text-right">Amount</TableHead>
                          <TableHead className="w-[9rem]">Status</TableHead>
                          <TableHead className="w-[11rem]">Deadline</TableHead>
                          <TableHead className="w-[11rem]">Paid</TableHead>
                          {/*
                            steward/116: `Tab` and `Settle` are the two actions an OPEN row can
                            carry, and `flex-wrap` on the cell below let them stack instead of
                            overflowing once this column actually became visible (steward/114). One
                            rem more of budget (70rem -> 71rem of 72) is what the ticket's own
                            arithmetic finds - still under the 72rem card, but unverified in a real
                            browser from here; if it still wraps or overflows at 1440px, the ticket
                            names the popover as the next step, not a wider column again.
                          */}
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
                                <PersonByIdentifier
                                  discordId={payment.discordId}
                                  people={people.data}
                                  avatarBaseUrl={avatarBase.data}
                                />
                              </TableCell>
                              <TableCell data-label="Days" className="text-right tnum">{payment.days}</TableCell>
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
                                    tipContent={
                                      state?.title ??
                                      "This interface does not know this status."
                                    }
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
                                {/* No `flex-wrap` here - see steward/116 on the header above. */}
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
                                      –
                                    </span>
                                  )}
                                  {/*
                                    steward/47: `settle` moved here from the generic command card,
                                    onto the one row it can apply to - an OPEN request already
                                    names the reference the command needs, so there is nothing
                                    left to pick. Money only moves for a request that can still be
                                    settled, hence `payment.status === "OPEN"` rather than drawing
                                    the button everywhere and disabling it.
                                  */}
                                  {payment.status === "OPEN" ? (
                                    <InlineCommandAction
                                      command={settleCommand}
                                      argumentName="reference"
                                      value={payment.reference}
                                      label="Settle"
                                      icon={HandCoinsIcon}
                                      confirmDescription={`Marks ${payment.reference} paid by hand and writes the access period it bought. Use this only once the money has actually arrived - it books access, it does not check bunq.`}
                                    />
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

// --- 3. /accounts -----------------------------------------------------------------------------------

/**
 * The identities: one Discord account, at most one Minecraft account, and the session you are
 * reading this in.
 *
 * There is no third, Steward-owned identity - and the card at the top says so rather than leaving
 * the reader to assume one exists. §10a wants a security key after Discord; this alpha does not
 * have one, `/api/me` says so in its own words, and those words are printed here verbatim.
 */
export function AccountsPage() {
  const people = usePeople()
  const avatarBase = useAvatarBaseUrl()
  const [needle, setNeedle] = useState("")
  const [onlyLinked, setOnlyLinked] = useState(false)
  const now = Date.now()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Accounts"
      />

      <AuthenticationCard />

      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium">Links</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div className="flex flex-wrap items-center gap-4">
            <div className="flex w-full min-w-0 flex-1 items-center gap-2 sm:min-w-64">
              <MagnifyingGlassIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
              <Input
                value={needle}
                onChange={(event) => setNeedle(event.target.value)}
                placeholder="Filter by Discord id or UUID…"
                aria-label="Filter by Discord id or UUID"
                autoComplete="off"
              />
            </div>
            <div className="flex items-center gap-2">
              <Switch id="only-linked" checked={onlyLinked} onCheckedChange={setOnlyLinked} />
              <Label htmlFor="only-linked">linked only</Label>
            </div>
          </div>

          <QueryState
            query={people}
            empty={{
              title: "Nobody yet",
              note: "The bot has not seen a single Discord account yet - or it is not running.",
            }}
            isEmpty={(list: Person[]) => list.length === 0}
          >
            {(list) => {
              if (list === undefined) {
                return (
                  <>
                    <IdentityTable>
                      {WAITING_PEOPLE.map((index) => (
                        <WaitingIdentityRow key={index} />
                      ))}
                    </IdentityTable>
                    <SkeletonText className="w-56 text-xs" />
                  </>
                )
              }
              const trimmed = needle.trim().toLowerCase()
              // Truthiness rather than `!== null`, on purpose: Javalin's Gson mapper drops nulls,
              // so `minecraftUuid` arrives ABSENT for an unlinked person even though `api.ts`
              // types it `string | null`. `!== null` would let every unlinked account through the
              // "linked only" filter, and the filter would look broken rather than wrong.
              const rows = list.filter(
                (person) =>
                  (!onlyLinked || Boolean(person.minecraftUuid)) &&
                  (trimmed === "" ||
                    person.discordId.toLowerCase().includes(trimmed) ||
                    (person.minecraftUuid ?? "").toLowerCase().includes(trimmed)),
              )
              if (rows.length === 0) {
                return (
                  <Empty
                    title="No account matches"
                    note="None of the loaded accounts contains this string."
                  />
                )
              }
              return (
                <>
                  <IdentityTable>
                      {rows.map((person) => (
                        <TableRow key={person.discordId}>
                          <TableCell data-label="Person" className="font-medium">
                            <PersonByIdentifier
                              discordId={person.discordId}
                              mcUuid={person.minecraftUuid}
                              people={people.data}
                              avatarBaseUrl={avatarBase.data}
                            />
                          </TableCell>
                          <TableCell data-label="Linked" className="text-muted-foreground tnum">
                            {person.linked ? dateTime(person.linked) : "–"}
                          </TableCell>
                          <TableCell data-label="Guild">
                            <MemberBadge state={person.memberState} />
                          </TableCell>
                          <TableCell data-label="Access">
                            <AccessBadge person={person} now={now} />
                          </TableCell>
                        </TableRow>
                      ))}
                  </IdentityTable>
                  <p className="text-xs text-muted-foreground">
                    {count(rows.length)} of {count(list.length)} loaded accounts. An account with
                    no link cannot reach the server, not even with paid access: the proxy knows only
                    Minecraft UUIDs.
                  </p>
                </>
              )
            }}
          </QueryState>
        </CardContent>
      </Card>
    </div>
  )
}

/**
 * The identities table's frame, for the same reason {@link PeopleTable} is one: four column widths
 * that have to be identical whether the roster is there or not (steward/120).
 */
function IdentityTable({ children }: { children: ReactNode }) {
  return (
    <Table className="steward-table">
      <TableHeader>
        <TableRow>
          <TableHead className="w-[20rem]">Person</TableHead>
          <TableHead className="w-[13rem]">Linked</TableHead>
          <TableHead className="w-[8rem]">Guild</TableHead>
          <TableHead>Access</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>{children}</TableBody>
    </Table>
  )
}

/** One identity before there is one. */
function WaitingIdentityRow() {
  return (
    <TableRow>
      <TableCell data-label="Person" className="font-medium">
        <div className="flex items-center gap-2">
          <Skeleton className="size-6 shrink-0 rounded-full" />
          <SkeletonText width="long" className="max-w-[10rem]" />
        </div>
      </TableCell>
      <TableCell data-label="Linked">
        <SkeletonText width="long" />
      </TableCell>
      <TableCell data-label="Guild">
        <Skeleton className="h-5 w-16 rounded-full" />
      </TableCell>
      <TableCell data-label="Access">
        <Skeleton className="h-5 w-28 rounded-full" />
      </TableCell>
    </TableRow>
  )
}

/**
 * What this interface accepts as proof of who you are - written down where somebody reads about
 * identities, because a gap here reads as "there is more, you just cannot see it".
 */
function AuthenticationCard() {
  const me = useMe()

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-sm font-medium">
          <ShieldCheckIcon className="size-4 text-muted-foreground" aria-hidden />
          Signing in to this interface
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-3">
          {/*
            THE BADGE FOLLOWS THE ANSWER, not the release notes. `keys` is what /api/me says this
            account has; a badge hard-coded to "not built" is what the last one was, and it stayed
            wrong for exactly as long as nobody re-read this file.
          */}
          {(me.data?.keys?.length ?? 0) > 0 ? (
            <StatusBadge tone="ok" tipContent="A key was registered and is required to be here at all.">
              Security key: registered
            </StatusBadge>
          ) : (
            <StatusBadge tone="warn" tipContent="§10a asks for a security key after Discord.">
              Security key: none on this account
            </StatusBadge>
          )}
          {me.data?.name ? (
            <span className="text-sm text-muted-foreground">
              signed in as <span className="text-foreground">{me.data.name}</span>
            </span>
          ) : null}
        </div>
        <QueryState query={me}>
          {(data) => (
            // The backend's own sentence, verbatim and in English: this is the API's answer and
            // not a claim this page makes on its behalf. The box is the same box either way - it
            // is two lines of monospace, and two lines is what it reserves while it waits.
            <pre className="overflow-auto rounded-sm bg-muted px-2 py-1 text-xs break-words whitespace-pre-wrap text-muted-foreground">
              {data ? (
                `/api/me\nwebauthn: ${data.webauthn}`
              ) : (
                <span className="flex flex-col gap-1">
                  <SkeletonText width="short" />
                  <SkeletonText width="medium" />
                </span>
              )}
            </pre>
          )}
        </QueryState>
      </CardContent>
    </Card>
  )
}

/** Ten rows of nothing while the record is read; the query hands out at most two hundred. */
const WAITING_ENTRIES = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]

// --- 4. /journal ----------------------------------------------------------------------------------

/**
 * The audit log.
 *
 * **Both filters are exact matches.** The backend compares the whole string, so this page offers
 * the actions as a list to choose from and asks for a subject explicitly rather than searching
 * while you type - a field that filters on every keystroke promises a substring search that does
 * not exist.
 *
 * The list of actions is built from the rows that are here. `audit_log.action` has no CHECK
 * constraint, and the writers are three: this interface (`GRANT_ACCESS`, `REVOKE_ACCESS`), the bot
 * (links, settlements) and the phase switch. A hardcoded list would be a page that cannot draw an
 * action somebody adds next week.
 */
export function JournalPage() {
  // The unfiltered query, for the options. With both filters empty it *is* the filtered query -
  // same key, one request - so this costs nothing until somebody actually filters.
  const all = useJournal("", "")
  const [action, setAction] = useState("")
  const [subject, setSubject] = useState("")
  const [typed, setTyped] = useState("")
  const entries = useJournal(action, subject)
  const actions = [...new Set((all.data ?? []).map((entry) => entry.action))].sort()
  // Same reason as on the payments page: a journal entry carries a Minecraft uuid and no name,
  // and steward/45 keeps the number out of the table. The roster is the cached query that knows
  // who it is; without it the identity says so rather than showing the uuid.
  const people = usePeople()
  const avatarBase = useAvatarBaseUrl()

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Journal"
      />

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
                      {value}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            {/*
              `w-64` on the field and a button beside it is 374px of a 356px card: on a phone the
              Filter button was drawn half off the screen. The field takes the row it is on and the
              buttons wrap under it; from `sm` the fixed width and the one-line row come back.
            */}
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
              The selector above lists only actions that occur in the loaded entries - none occurs
              yet.
            </p>
          ) : null}

          <QueryState
            query={entries}
            empty={{
              title: "No entry",
              note: "Nothing in the record matches these filters. Both compare exactly, not partially - a typo in the id looks exactly like \"nothing happened\".",
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
                        {/* The action is printed raw, exactly as the row carries it: any prettier
                         * wording would be a table that silently falls back to the enum name for
                         * anything new - and this column is what somebody greps the bot's log for. */}
                        <TableCell data-label="Action" className="font-medium">{entry.action}</TableCell>
                        {/* steward/124, Till on 2026-09-19: never user ids, always profiles.
                         * Both columns printed the raw snowflake until then. The
                         * identity component is what the rest of the app already uses, and it
                         * answers the awkward case by itself: somebody the roster no longer knows
                         * is drawn as "no Discord name on record" with the id still copyable in
                         * the popover, which is a name for the row rather than an anonymous one.
                         * No admin at all is Steward's own mark, the case `system` was built for. */}
                        <TableCell data-label="Triggered by" className="text-muted-foreground">
                          {entry.actor ? (
                            <PersonByIdentifier
                              discordId={entry.actor}
                              people={people.data}
                              avatarBaseUrl={avatarBase.data}
                            />
                          ) : (
                            <PersonIdentity system />
                          )}
                        </TableCell>
                        <TableCell data-label="Concerns" className="text-muted-foreground">
                          {entry.subject || entry.mcUuid ? (
                            <PersonByIdentifier
                              discordId={entry.subject}
                              mcUuid={entry.mcUuid}
                              people={people.data}
                              avatarBaseUrl={avatarBase.data}
                            />
                          ) : null}
                        </TableCell>
                        {/* steward/114: the one column here that is running text rather than a
                         * field - a field does not wrap (a date, an id), prose does, at any
                         * width, so this overrides TableCell's own `whitespace-nowrap` rather
                         * than relying on the stacked layout alone. */}
                        <TableCell data-label="Detail" className="text-muted-foreground whitespace-normal">
                          {entry.detail ?? "–"}
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
                      {count(list.length)} entries. This query hands out no more than 200 - paging
                      through the whole record is not something the API knows yet.
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
