import { useEffect } from "react"
import { useQueryClient, type Query } from "@tanstack/react-query"

import type { Topic } from "@/lib/api.gen"
import { followStream } from "@/lib/event-stream"

export type { Topic }

export const RECONCILE = 60_000

/**
 * Returns the options that make a query live: read again when one of its topics changes, and once a minute anyway.
 */
export function live(...topics: Topic[]) {
  return { meta: { topics }, refetchInterval: RECONCILE } as const
}

function topicsOf(query: Query): readonly unknown[] {
  const topics: unknown = query.meta?.topics
  return Array.isArray(topics) ? topics : []
}

export function announce(client: ReturnType<typeof useQueryClient>, topic: Topic) {
  void client.invalidateQueries({ predicate: (query) => topicsOf(query).includes(topic) })
}

function isTopicChange(value: unknown): value is { topic: Topic } {
  return typeof value === "object" && value !== null && "topic" in value && typeof value.topic === "string"
}

/**
 * Holds the one live stream while somebody is signed in. Every reconnect reads every live query again, because a change may have passed while the stream was down.
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
