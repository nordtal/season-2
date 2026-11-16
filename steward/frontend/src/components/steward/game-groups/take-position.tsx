import { CursorClickIcon, XIcon } from "@/components/ui/icons"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { useEffect, useRef, useState } from "react"
import { toast } from "sonner"

import { api, errorMessage, type CommandAsked, type CommandRun, type ConfigEntry, type PositionTaker } from "@/lib/api"
import { finalRun } from "@/lib/queries-commands"
import { RECONCILE } from "@/lib/live"
import { keys } from "@/lib/query-keys"
import { message, t } from "@/lib/texts"
import type { PlaceShape } from "@/components/steward/game-groups/model"
import { Button } from "@/components/ui/button"

/** Where the admin's player stood and faced, or the block it looked at, as the server answered. */
export type TakenPosition = { world: string; x: number; y: number; z: number; yaw: number; pitch: number }

/** Answers the place a done take carries, or undefined when the answer lacks a part of it. */
export function takenOf(run: CommandRun): TakenPosition | undefined {
  const answer = run.answer ?? {}
  const { world, x, y, z, yaw, pitch } = answer
  if (typeof world !== "string") return undefined
  if (typeof x !== "number" || typeof y !== "number" || typeof z !== "number") return undefined
  if (typeof yaw !== "number" || typeof pitch !== "number") return undefined
  return { world, x, y, z, yaw, pitch }
}

/**
 * The values a taken position fills in a place of `shape`, by key: only the keys the place has, and a whole block
 * where the field holds whole numbers. An area takes `corner` 0 into its min keys and 1 into its max keys; the box
 * is spanned between them, so the corners may be taken in either order.
 */
export function takenValues(
  shape: PlaceShape,
  fields: ConfigEntry[],
  taken: TakenPosition,
  corner: 0 | 1 = 0,
): Record<string, string> {
  const side = corner === 0 ? "min" : "max"
  const source: Record<string, string | number> =
    shape === "area"
      ? { world: taken.world, [`${side}-x`]: taken.x, [`${side}-y`]: taken.y, [`${side}-z`]: taken.z }
      : shape === "centre"
        ? { x: taken.x, z: taken.z }
        : shape === "block"
          ? { world: taken.world, x: taken.x, y: taken.y, z: taken.z }
          : { ...taken }
  const values: Record<string, string> = {}
  for (const field of fields) {
    const value = source[field.key]
    if (value === undefined) continue
    values[field.key] =
      typeof value === "number" && field.type === "INTEGER" ? String(Math.floor(value)) : String(value)
  }
  return values
}

/** Why the admin's player cannot take a position now, or null when it can, or while Steward has not said. */
function blockedBy(taker: PositionTaker | undefined): string | null {
  if (taker === undefined || taker.taker === "READY") return null
  return taker.taker === "UNLINKED" ? t("steward.answer.preview-unlinked") : t("steward.answer.take-in-offline")
}

function withdraw(id: string) {
  api<void>(`/api/commands/${id}`, { method: "DELETE" }).catch((error: unknown) =>
    toast.error(t("steward.game.take-position-failed"), { description: errorMessage(error) }),
  )
}

/**
 * The buttons that fill a place from the game: "Take my position" for a spot or a centre, "Take the block I look at"
 * for a block, and one per corner for an area. Without a linked player, or while it is not on smp, they are off and a
 * line says why. While a take waits its button withdraws it, and so does closing the dialog.
 */
export function TakePosition({
  shape,
  disabled,
  onTaken,
}: {
  shape: PlaceShape
  disabled: boolean
  onTaken: (taken: TakenPosition, corner: 0 | 1) => void
}) {
  const client = useQueryClient()
  const taker = useQuery({
    queryKey: keys.positionTaker,
    queryFn: () => api<PositionTaker>("/api/smp/take-position"),
    // No topic tells when a player joins or leaves, so it is read again whenever the admin returns from the game.
    refetchInterval: RECONCILE,
    refetchOnMount: "always",
    refetchOnWindowFocus: "always",
  })
  const [waiting, setWaiting] = useState<{ id: string; corner: 0 | 1 } | null>(null)
  const [asking, setAsking] = useState(false)
  // The take this field still waits for; a run of any other is stale.
  const current = useRef<string | null>(null)
  const gone = useRef(false)
  const taken = useRef(onTaken)
  useEffect(() => {
    taken.current = onTaken
  }, [onTaken])
  useEffect(() => {
    gone.current = false
    return () => {
      gone.current = true
      if (current.current !== null) withdraw(current.current)
    }
  }, [])

  const ended = (id: string, corner: 0 | 1, run: CommandRun) => {
    if (current.current !== id) return
    current.current = null
    setWaiting(null)
    const place = run.status === "DONE" ? takenOf(run) : undefined
    // A refused or expired take may mean the player left the server since Steward last said.
    if (!place) void client.invalidateQueries({ queryKey: keys.positionTaker })
    if (place) taken.current(place, corner)
    else if (run.status === "EXPIRED") toast.error(t("steward.answer.take-in-offline"))
    else if (run.status !== "CANCELLED")
      toast.error(t("steward.game.take-position-failed"), { description: run.result ? message(run.result) : undefined })
  }

  const ask = async (corner: 0 | 1) => {
    setAsking(true)
    try {
      const block = shape === "area" || shape === "block"
      const asked = await api<CommandAsked>("/api/smp/take-position", { method: "POST", body: { block } })
      // The dialog closed while the request was on its way.
      if (gone.current) {
        withdraw(asked.id)
        return
      }
      current.current = asked.id
      setWaiting({ id: asked.id, corner })
      finalRun(client, asked.id).then(
        (run) => ended(asked.id, corner, run),
        (error: unknown) => {
          if (current.current !== asked.id) return
          current.current = null
          setWaiting(null)
          toast.error(t("steward.game.take-position-failed"), { description: errorMessage(error) })
        },
      )
    } catch (error) {
      toast.error(t("steward.game.take-position-failed"), { description: errorMessage(error) })
      void client.invalidateQueries({ queryKey: keys.positionTaker })
    } finally {
      if (!gone.current) setAsking(false)
    }
  }

  const cancel = () => {
    if (waiting === null) return
    current.current = null
    setWaiting(null)
    withdraw(waiting.id)
  }

  const blocked = blockedBy(taker.data)
  const takes: { corner: 0 | 1; label: string }[] =
    shape === "area"
      ? [
          { corner: 0, label: t("steward.game.take-corner", { index: 1 }) },
          { corner: 1, label: t("steward.game.take-corner", { index: 2 }) },
        ]
      : [{ corner: 0, label: t(shape === "block" ? "steward.game.take-block" : "steward.game.take-position") }]
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex flex-wrap gap-2">
        {takes.map((take) =>
          waiting?.corner === take.corner ? (
            <Button key={take.corner} type="button" variant="destructive" className="max-sm:w-full" onClick={cancel}>
              <XIcon aria-hidden />
              {t("steward.game.cancel-take-position")}
            </Button>
          ) : (
            <Button
              key={take.corner}
              type="button"
              variant="outline"
              disabled={disabled || asking || waiting !== null || blocked !== null}
              className="max-sm:w-full"
              onClick={() => void ask(take.corner)}
            >
              <CursorClickIcon aria-hidden />
              {take.label}
            </Button>
          ),
        )}
      </div>
      {blocked !== null && !disabled ? <p className="text-xs text-muted-foreground">{blocked}</p> : null}
    </div>
  )
}
