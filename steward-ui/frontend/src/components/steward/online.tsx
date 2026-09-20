import type { ReactNode } from "react"

import { useAvatarBaseUrl, useServices } from "@/lib/queries"
import { count } from "@/lib/format"
import { MinecraftHead } from "@/components/steward/identity"
import { Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"

/**
 * WHO IS IN THE GAME, AT THE TOP OF THE START PAGE (steward/64).
 *
 * Till, 2026-09-17: the word "Overview" at the top of the start page is a label on a page nobody
 * reached by accident, and it goes. What takes its place is the one thing about this stack that
 * changes minute to minute and that no tile below says - how many people are actually in the game
 * right now. He named a shape (up to three faces, then a `+N`, then the count in words) and then
 * asked for alternatives to it, explicitly including one with Minecraft heads overlapping as a
 * triangle rather than sitting in a pill. The three below are those alternatives.
 *
 * <h2>The count is real; the faces are not available yet, and that is not a bug in this file</h2>
 * `online_count` is counts and nothing else - its own migration says so in as many words: *"an
 * identifier, not prose … never a container id and never a player name."* proxy knows who
 * is connected (it has the Velocity API and a `LoginRoster`), but nothing writes that down, so
 * Steward can know that seven people are playing and cannot know which seven.
 *
 * Rather than draw invented faces, the shape is built the other way round: **the `+N` is the
 * general case and a face is the enrichment.** With no roster at all, the stack is a single circle
 * reading `+7` beside "7 players online" - the same object, the same size, the same line, and not
 * one pixel of it is a claim the data does not support. The day a roster exists, three heads move
 * in front of it and the `+N` becomes `+4` on its own. steward/111 is that roster.
 *
 * <h2>Nobody online is the normal case on this host, so it is drawn, not handled</h2>
 * The number is the anchor of all three variants and the faces are what hangs off it, which is
 * exactly why none of them falls apart at zero: `0 players online` is a sentence about a fact.
 * What must never happen is the other thing - a settled `0` where the truth is "proxy has
 * not written recently enough to be believed". That is a dash, everywhere, the same rule
 * `ServicesApi` and the metric row already follow. `players` being absent is load-bearing and
 * `players ?? 0` is the one expression this file may not contain.
 */

/** One person in the game. Both fields are optional because a roster may know only one of them. */
export type OnlinePlayer = { uuid?: string; name?: string }

/** The three Minecraft services that carry their own count, in the order a player passes them. */
const SERVERS = ["limbo", "hunger-games", "smp"] as const

export type Online = {
  /** The network total, from `proxy`. `undefined` means nobody has said, not nobody. */
  total?: number
  /** Per server, in {@link SERVERS} order, and only the ones that answered. */
  servers: Array<{ service: string; players: number }>
  /** Who, if anything ever writes it down. Empty today - see the note at the top of this file. */
  roster: OnlinePlayer[]
  pending: boolean
}

/**
 * The one query all three variants read, so they are compared on the same numbers.
 *
 * The total is `proxy`'s own row rather than the sum of the three servers: a player is on
 * exactly one backend, so the two agree whenever every row is fresh - and when one is not, the sum
 * silently drops that server's people while the proxy's own count still has them.
 */
export function useOnline(roster?: OnlinePlayer[]): Online {
  const services = useServices()
  const rows = services.data?.services ?? []
  const proxy = rows.find((row) => row.service === "proxy")
  return {
    total: proxy?.players,
    servers: SERVERS.flatMap((name) => {
      const row = rows.find((service) => service.service === name)
      return row?.players === undefined ? [] : [{ service: name, players: row.players }]
    }),
    // steward/111: from the same row the total comes from, and not from a parameter nobody
    // passed. The worker has been putting a `roster` on this row since the roster existed; the
    // page called `useOnline()` with no argument, so the stack drew a count and never a face -
    // which is exactly what Till saw as the only player online: `+1`, and no head. An explicit
    // argument still wins, for a caller that has a better list than the network's.
    roster: roster ?? proxy?.roster ?? [],
    pending: services.isPending,
  }
}

/** "7 players online", "1 player online", "0 players online" - and a dash for "nobody has said". */
function said(total: number | undefined): { number: string; word: string } {
  if (total === undefined) return { number: "–", word: "players online" }
  return { number: count(total), word: total === 1 ? "player online" : "players online" }
}

/**
 * The faces, overlapping, with the overflow as the last circle.
 *
 * <h2>What the web had to say, and what was taken from it</h2>
 * Three to five faces before the rest collapse is the going figure; Till said three, which is at
 * the tight end of it and right for a line that also has to hold a number at 390px. The part worth
 * taking was about the overflow: **`+N` is not decoration.** It is a fact nobody can otherwise
 * reach, so it carries its own name for a screen reader, and the stack itself is a list with one
 * entry per person rather than a row of identical unnamed images.
 */
function Stack({
  online,
  size = "size-8",
  base,
}: {
  online: Online
  size?: string
  base: string | undefined
}) {
  const shown = online.roster.slice(0, 3)
  // Never negative, and never computed from the faces alone: with no roster the overflow IS the
  // whole count, which is the state this interface is actually in today.
  const rest = Math.max((online.total ?? 0) - shown.length, 0)

  // steward/120: `pending` has been arriving on this object since it existed and was read by
  // nobody. One circle, because one circle is the shape this stack has on almost every day of the
  // season - and because an invented number of them would be a guess about how busy the server is.
  if (online.pending) {
    return (
      <ul className="flex shrink-0 items-center -space-x-2">
        <li className="rounded-md ring-2 ring-background">
          <Skeleton className={`${size} rounded-md`} />
        </li>
      </ul>
    )
  }
  if (online.total === undefined || online.total === 0) return null

  return (
    <ul className="flex shrink-0 items-center -space-x-2">
      {shown.map((player, index) => (
        <li key={player.uuid ?? player.name ?? index} className="rounded-md ring-2 ring-background">
          <Tooltip>
            <TooltipTrigger asChild>
              <span>
                <MinecraftHead mcUuid={player.uuid ?? ""} baseUrl={base} size={size} rounded="rounded-md" />
              </span>
            </TooltipTrigger>
            <TooltipContent>{player.name ?? "no name on record"}</TooltipContent>
          </Tooltip>
        </li>
      ))}
      {rest > 0 ? (
        <li
          className={`${size} flex items-center justify-center rounded-md bg-secondary text-xs font-medium tabular-nums text-foreground ring-2 ring-background`}
          role="img"
          aria-label={`${rest} more in the game`}
        >
          +{count(rest)}
        </li>
      ) : null}
    </ul>
  )
}

/** Where they are, as numbers - the one thing the heading can add that the tiles below do not. */
function Where({ online, className }: { online: Online; className?: string }) {
  if (online.pending) {
    return (
      <div className={`flex items-center gap-3 ${className ?? ""}`}>
        <SkeletonText className="w-14 text-xs" />
        <SkeletonText className="w-20 text-xs" />
      </div>
    )
  }
  if (online.servers.length === 0) return null
  return (
    <ul className={`flex flex-wrap items-center gap-x-3 gap-y-0.5 text-xs text-muted-foreground ${className ?? ""}`}>
      {online.servers.map((row) => (
        <li key={row.service} className="flex items-center gap-1">
          <span className="tabular-nums text-foreground">{count(row.players)}</span>
          <span>{row.service}</span>
        </li>
      ))}
    </ul>
  )
}

/**
 * VARIANT A - the line Till quoted, without the pill.
 *
 * Faces, the overflow, then the count in words, all on one line. It is the reference shape from
 * shadcnstudio's avatar 20 and 21 with the one change Till made when he offered the alternatives:
 * no pill around it. The pill is what turns a group of people into a control, and nothing here is
 * tappable.
 *
 * The number carries the weight the `h1` it replaces used to carry - this is the top of the page,
 * and a line of small grey text there reads as a caption for the tiles rather than as the page's
 * own first statement.
 */
export function OnlineLine({ online }: { online: Online }) {
  const base = useAvatarBaseUrl().data
  const { number, word } = said(online.total)
  return (
    <Heading>
      <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1">
        <Stack online={online} base={base} />
        {online.pending ? (
          <p className="flex items-baseline gap-1.5">
            <SkeletonText className="w-10 text-2xl" />
            <SkeletonText className="w-24 text-sm" />
          </p>
        ) : (
          <p className="flex items-baseline gap-1.5">
            <span className="text-2xl font-semibold tabular-nums tracking-tight">{number}</span>
            <span className="text-sm text-muted-foreground">{word}</span>
          </p>
        )}
      </div>
      <Where online={online} />
    </Heading>
  )
}

/**
 * VARIANT B - the triangle Till asked for himself.
 *
 * Three heads, overlapping, two below and one above the gap between them, which is the arrangement
 * he described. It is the only variant whose shape says something the number does not: a cluster
 * reads as a group of people standing together, where a row reads as a list of them. The overflow
 * sits at the lower right of the cluster rather than in line with it, because a fourth circle in
 * the row would flatten the triangle back into a stack.
 *
 * It is also the variant with the most to lose: with nobody in the game there is no cluster, and
 * what is left is the number and the word. That is not a failure of the idea, it is what the idea
 * costs, and it is the reason all three are being shown rather than one.
 */
export function OnlineCluster({ online }: { online: Online }) {
  const base = useAvatarBaseUrl().data
  const { number, word } = said(online.total)
  const shown = online.roster.slice(0, 3)
  const rest = Math.max((online.total ?? 0) - shown.length, 0)
  const anybody = (online.total ?? 0) > 0

  return (
    <Heading>
      <div className="flex items-center gap-3">
        {anybody ? (
          <div className="relative size-11 shrink-0" aria-hidden={shown.length === 0}>
            {shown[0] ? (
              <span className="absolute left-1/2 top-0 -translate-x-1/2 rounded-md ring-2 ring-background">
                <MinecraftHead mcUuid={shown[0].uuid ?? ""} baseUrl={base} size="size-6" rounded="rounded-md" />
              </span>
            ) : null}
            {shown[1] ? (
              <span className="absolute bottom-0 left-0 rounded-md ring-2 ring-background">
                <MinecraftHead mcUuid={shown[1].uuid ?? ""} baseUrl={base} size="size-6" rounded="rounded-md" />
              </span>
            ) : null}
            {shown[2] ? (
              <span className="absolute bottom-0 right-0 rounded-md ring-2 ring-background">
                <MinecraftHead mcUuid={shown[2].uuid ?? ""} baseUrl={base} size="size-6" rounded="rounded-md" />
              </span>
            ) : null}
            {rest > 0 ? (
              <span
                className="absolute bottom-0 right-0 flex size-6 translate-x-1.5 translate-y-1 items-center justify-center rounded-md bg-secondary text-[0.625rem] font-medium tabular-nums text-foreground ring-2 ring-background"
                role="img"
                aria-label={`${rest} more in the game`}
              >
                +{count(rest)}
              </span>
            ) : null}
          </div>
        ) : null}
        <p className="flex items-baseline gap-1.5">
          <span className="text-2xl font-semibold tabular-nums tracking-tight">{number}</span>
          <span className="text-sm text-muted-foreground">{word}</span>
        </p>
      </div>
      <Where online={online} />
    </Heading>
  )
}

/**
 * VARIANT C - the number is the heading, the faces are the footnote.
 *
 * The figure at the size a page title has, the word under it, and the faces small and to the side.
 * It is the variant that reads the same whether three people are on or none, because nothing about
 * its shape depends on there being faces at all - the empty case costs it nothing, and the full
 * case gains it least. That is the trade, stated so it can be chosen rather than discovered.
 */
export function OnlineFigure({ online }: { online: Online }) {
  const base = useAvatarBaseUrl().data
  const { number, word } = said(online.total)
  return (
    <Heading>
      <div className="flex min-w-0 items-end gap-3">
        <p className="flex flex-col">
          <span className="text-4xl leading-none font-semibold tabular-nums tracking-tight">
            {number}
          </span>
          <span className="mt-1 text-sm text-muted-foreground">{word}</span>
        </p>
        <div className="flex flex-col items-start gap-1 pb-0.5">
          <Stack online={online} base={base} size="size-6" />
          <Where online={online} />
        </div>
      </div>
    </Heading>
  )
}

/**
 * The row the heading stands in.
 *
 * It is a `header` with no border, no background and no height of its own - the page's own
 * {@link PageHeader} is gone from the start page, and what replaces it must not become a bar. The
 * actions a page header carried on the right are not reinstated here: the start page had none.
 */
function Heading({ children }: { children: ReactNode }) {
  return <header className="flex flex-col gap-1.5">{children}</header>
}
