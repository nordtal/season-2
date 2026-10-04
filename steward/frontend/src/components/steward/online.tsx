import type { ReactNode } from "react"

import { useAvatarBaseUrl, useService, useServices } from "@/lib/queries"
import { count } from "@/lib/format"
import { MinecraftHead } from "@/components/steward/identity"
import { Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { t } from "@/lib/texts"

/**
 * Who is in the game, at the top of the start page: up to three faces, then `+N`, then the count in words.
 *
 * An unknown count is a dash, never a settled `0`, so `players ?? 0` must not appear here.
 */

/** One person in the game; a roster may know only one of the two fields. */
export type OnlinePlayer = { uuid?: string; name?: string }

/** The three Minecraft services that carry their own count, in the order a player passes them. */
const SERVERS = ["limbo", "hunger-games", "smp"] as const

export type Online = {
  /** The network total, from `proxy`; `undefined` means nobody has said, not nobody. */
  total?: number
  /** Per server, in {@link SERVERS} order, only those that answered. */
  servers: Array<{ service: string; players: number }>
  /** Who is online, where a roster exists; the `+N` covers everyone else. */
  roster: OnlinePlayer[]
  pending: boolean
}

/** The one query all three variants read; the total is `proxy`'s own row, not the sum of the servers. */
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
    /** The proxy row's roster unless the caller passes a better one. */
    roster: roster ?? proxy?.roster ?? [],
    pending: services.isPending,
  }
}

/** "7 players online", "1 player online", or a dash when nobody has said. */
function said(total: number | undefined): { number: string; word: string } {
  if (total === undefined) return { number: "\u2013", word: "players online" }
  return { number: count(total), word: total === 1 ? "player online" : "players online" }
}

/** The faces, overlapping, with the overflow as a named last circle. */
function Stack({ online, size = "size-8", base }: { online: Online; size?: string; base: string | undefined }) {
  const shown = online.roster.slice(0, 3)
  /** Never negative; with no roster the overflow is the whole count. */
  const rest = Math.max((online.total ?? 0) - shown.length, 0)

  /** Pending draws a single circle, since any more would guess how busy the server is. */
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
            <TooltipContent>{player.name ?? t("steward.network.no-name")}</TooltipContent>
          </Tooltip>
        </li>
      ))}
      {rest > 0 ? (
        <li
          className={`${size} flex items-center justify-center rounded-md bg-secondary text-xs font-medium tabular-nums text-foreground ring-2 ring-background`}
          role="img"
          aria-label={t("steward.network.more", { count: rest })}
        >
          +{count(rest)}
        </li>
      ) : null}
    </ul>
  )
}

/** Where they are, per server, which the tiles below do not say. */
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

/** Variant A: faces, overflow and count on one line, without a pill, since nothing here is tappable. */
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

/** Variant B: three faces as a triangle, with the overflow at its lower right. */
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
                aria-label={t("steward.network.more", { count: rest })}
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

/** Variant C: the number as the heading, the faces small beside it. */
export function OnlineFigure({ online }: { online: Online }) {
  const base = useAvatarBaseUrl().data
  const { number, word } = said(online.total)
  return (
    <Heading>
      <div className="flex min-w-0 items-end gap-3">
        <p className="flex flex-col">
          <span className="text-4xl leading-none font-semibold tabular-nums tracking-tight">{number}</span>
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

/** The row the heading stands in: a `header` with no border, background or height of its own. */
function Heading({ children }: { children: ReactNode }) {
  return <header className="flex flex-col gap-1.5">{children}</header>
}

/**
 * The same line on a service page, counted for that service alone.
 *
 * Undefined where the row carries no `players`, so there is no skeleton.
 */
export function useServiceOnline(name: string): Online | undefined {
  const service = useService(name)
  const players = service.data?.players
  if (players === undefined) return undefined
  return { total: players, servers: [], roster: service.data?.roster ?? [], pending: false }
}

export function ServiceOnlineLine({ name }: { name: string }) {
  const online = useServiceOnline(name)
  const base = useAvatarBaseUrl().data
  if (!online) return null
  const { number, word } = said(online.total)
  return (
    <div className="flex min-w-0 items-center gap-x-3">
      <Stack online={online} base={base} size="size-7" />
      <p className="flex items-baseline gap-1.5">
        <span className="text-lg font-semibold tabular-nums tracking-tight">{number}</span>
        <span className="text-sm text-muted-foreground">{word}</span>
      </p>
    </div>
  )
}
