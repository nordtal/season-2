import { useEffect } from "react"
import { useQueryClient, type Query } from "@tanstack/react-query"

import { followStream } from "@/lib/event-stream"

/** What steward's live stream announces a change of, one name per `live.Topic` on the server. */
export type Topic =
  | "RUNS"
  | "REQUESTS"
  | "JOURNAL"
  | "PEOPLE"
  | "SEASON"
  | "GAMES"
  | "SETTINGS"
  | "SERVICES"
  | "HOST"
  | "METRICS"
  | "ALERTS"
  | "TOPOLOGY"
  | "GAME_DATA"

/** How often a live query is read regardless, in case the stream missed something. */
export const RECONCILE = 60_000

/** The options that make a query live: read again when one of its topics changes, and once a minute anyway. */
export function live(...topics: Topic[]) {
  return { meta: { topics }, refetchInterval: RECONCILE } as const
}

/** The topics a query in the cache follows, from the `meta` that {@link live} put there. */
function topicsOf(query: Query): readonly unknown[] {
  const topics: unknown = query.meta?.topics
  return Array.isArray(topics) ? topics : []
}

const waiting = new Set<(topic: Topic) => void>()

/** A listener that hears nothing, until the promise below puts the real one in its place. */
const ignore = () => undefined

/** A wait for one topic's next change; {@link stop} ends it early. */
export type Change = { arrived: Promise<void>; stop: () => void }

/**
 * Waits for the next change of `topic`, or a minute when none comes.
 *
 * Asked before the read it follows, so a change between the read and the wait is not missed.
 */
export function nextChange(topic: Topic, within = RECONCILE): Change {
  let timer: ReturnType<typeof setTimeout> | undefined
  let heard: (changed: Topic) => void = ignore
  const arrived = new Promise<void>((resolve) => {
    heard = (changed) => {
      if (changed !== topic) return
      waiting.delete(heard)
      clearTimeout(timer)
      resolve()
    }
  })
  timer = setTimeout(() => heard(topic), within)
  waiting.add(heard)
  return { arrived, stop: () => heard(topic) }
}

/** Hands one change to the cache and to whoever waits for it; the stream's handler, exported for tests. */
export function announce(client: ReturnType<typeof useQueryClient>, topic: Topic) {
  void client.invalidateQueries({ predicate: (query) => topicsOf(query).includes(topic) })
  for (const heard of waiting) heard(topic)
}

function isTopicChange(value: unknown): value is { topic: Topic } {
  return typeof value === "object" && value !== null && "topic" in value && typeof value.topic === "string"
}

/**
 * Holds the one live stream while somebody is signed in, and turns each change into a refetch of its queries.
 *
 * Every reconnect reads every live query again, since a change may have passed while the stream was down.
 */
export function useLiveStream(enabled: boolean) {
  const client = useQueryClient()
  useEffect(() => {
    if (!enabled || typeof EventSource === "undefined") return undefined
    let first = true
    return followStream("/api/live", {
      events: {
        change: (data) => {
          const parsed: unknown = JSON.parse(data)
          if (isTopicChange(parsed)) announce(client, parsed.topic)
        },
      },
      opened: () => {
        if (!first) void client.invalidateQueries({ predicate: (query) => topicsOf(query).length > 0 })
        first = false
      },
    })
  }, [client, enabled])
}
