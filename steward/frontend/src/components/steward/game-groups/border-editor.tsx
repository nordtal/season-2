import { CursorClickIcon, PlusIcon } from "@/components/ui/icons"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { useCallback, useEffect, useRef, useState } from "react"
import { toast } from "sonner"

import {
  api,
  errorMessage,
  type CommandAsked,
  type CommandRun,
  type ConfigDocument,
  type ConfigEntry,
  type PositionTaker,
} from "@/lib/api"
import { announceSave } from "@/lib/announce-save"
import { setDraftValue, useDraft } from "@/lib/drafts"
import { finalRun } from "@/lib/queries-commands"
import { RECONCILE } from "@/lib/live"
import { keys } from "@/lib/query-keys"
import { useConfig, useSaveConfig } from "@/lib/queries"
import { message, t } from "@/lib/texts"
import { type Draft, draftSections } from "@/components/steward/configuration"
import type { CustomEditorProps } from "@/components/steward/config-editors"
import { blankSection, type SectionValues } from "@/components/steward/repeatable-cards"
import { DraftForm } from "@/components/steward/settings-config"
import { type DraftValue, isDraftValueRecord } from "@/components/steward/group-draft"
import { blockedBy } from "@/components/steward/game-groups/take-position"
import { listEntry, text } from "@/components/steward/game-groups/model"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"

const KEYS = ["world", "polygons"]

/** The highest block on either axis of the overworld's map, as smp checks it. */
const MAP_MAX = 10239

/** How far an overworld border region keeps from the edge of the map, so the vanilla border stays out of sight. */
export const EDGE_MARGIN = 160

/** A shape of the draft in the game, as smp answers a step of it. */
export type AreaShape = { name: string; corners: { x: number; z: number }[]; bottom: number; top: number }

export function readsCustomBorder(document: ConfigDocument): boolean {
  const worlds = listEntry(document.entries, KEYS)
  return worlds?.template?.find((candidate) => candidate.key === "polygons")?.kind === "SECTIONS"
}

function isCorner(value: unknown): value is { x: number; z: number } {
  return (
    typeof value === "object" &&
    value !== null &&
    "x" in value &&
    typeof value.x === "number" &&
    "z" in value &&
    typeof value.z === "number"
  )
}

function isAreaShape(value: unknown): value is AreaShape {
  return (
    typeof value === "object" &&
    value !== null &&
    "name" in value &&
    typeof value.name === "string" &&
    "bottom" in value &&
    typeof value.bottom === "number" &&
    "top" in value &&
    typeof value.top === "number" &&
    "corners" in value &&
    Array.isArray(value.corners) &&
    value.corners.every(isCorner)
  )
}

/** Returns the shapes a done draft step carries, or undefined when the answer has another shape. */
export function shapesOf(run: CommandRun): { world: string; shapes: AreaShape[] } | undefined {
  const { world, shapes } = run.answer ?? {}
  if (typeof world !== "string" || !Array.isArray(shapes) || !shapes.every(isAreaShape)) return undefined
  return { world, shapes }
}

/** The polygons of a world's card, as the draft in the game starts with them. Corners that hold no number are left out. */
export function shapesFrom(world: SectionValues | undefined): AreaShape[] {
  const polygons = world?.polygons
  if (!Array.isArray(polygons)) return []
  return polygons.flatMap((polygon) =>
    typeof polygon === "string"
      ? []
      : [
          {
            name: text(polygon, "name"),
            corners: (Array.isArray(polygon.corners) ? polygon.corners : []).flatMap((corner) => {
              if (typeof corner === "string") return []
              const x = Number(text(corner, "x"))
              const z = Number(text(corner, "z"))
              return text(corner, "x") === "" ||
                text(corner, "z") === "" ||
                !Number.isInteger(x) ||
                !Number.isInteger(z)
                ? []
                : [{ x, z }]
            }),
            bottom: 0,
            top: 0,
          },
        ],
  )
}

/** The world cards with `world`'s polygons replaced by `shapes`, and a card added when the world has none. */
export function withShapes(
  worlds: SectionValues[],
  template: ConfigEntry[],
  world: string,
  shapes: AreaShape[],
): SectionValues[] {
  const polygons: SectionValues[] = shapes.map((shape) => ({
    name: shape.name,
    corners: shape.corners.map((corner) => ({ x: String(corner.x), z: String(corner.z) })),
  }))
  const at = worlds.findIndex((entry) => text(entry, "world") === world)
  if (at < 0) return [...worlds, { ...blankSection(template), world, polygons }]
  return worlds.map((entry, index) => (index === at ? { ...entry, polygons } : entry))
}

/** The fewest blocks between a corner of `shape` and the edge of the overworld's map. */
export function edgeDistance(shape: AreaShape): number {
  return Math.min(...shape.corners.map(({ x, z }) => Math.min(x, MAP_MAX - x, z, MAP_MAX - z)))
}

/** The open in-game draft, when one is active for the admin's linked player. */
export type LiveDraft = { world: string; shapes: AreaShape[] }

/** A line per overworld region closer than the margin to the map's edge. Saving is never refused for it. */
export function edgeWarnings(
  worlds: SectionValues[],
  overworld: string | undefined,
  liveDraft?: LiveDraft | null,
): string[] {
  if (overworld === undefined) return []
  const shapes =
    liveDraft !== undefined && liveDraft !== null && liveDraft.world === overworld
      ? liveDraft.shapes
      : shapesFrom(worlds.find((entry) => text(entry, "world") === overworld))
  return shapes
    .filter((shape) => shape.corners.length > 0)
    .flatMap((shape, index) => {
      const distance = edgeDistance(shape)
      return distance < EDGE_MARGIN
        ? [t("steward.game.area-near-edge", { name: shape.name || String(index + 1), distance, margin: EDGE_MARGIN })]
        : []
    })
}

async function ask(client: ReturnType<typeof useQueryClient>, path: string, body: unknown): Promise<CommandRun> {
  const asked = await api<CommandAsked>(path, { method: "POST", body })
  return finalRun(client, asked.id)
}

/**
 * The custom border's settings with the area editor above them. "Edit in the game" gives the admin's player a
 * draft of one world's polygons, which only that admin sees. Save writes the draft into the world's card and saves
 * the group at once, and Discard drops the draft.
 */
export function CustomBorderEditor({ file, document, target }: CustomEditorProps) {
  const worlds = listEntry(document.entries, KEYS)
  const draft = useDraft<DraftValue>(file, isDraftValueRecord) as Draft
  const overworld = useConfig(`${document.service}/worlds`).data?.entries.find(
    (entry) => entry.path === "world-nordtal",
  )?.value
  const save = useSaveConfig(document.path)
  const [liveDraft, setLiveDraft] = useState<LiveDraft | null>(null)
  if (worlds === undefined) return null
  const cards = draftSections(draft, worlds)
  const warnings = edgeWarnings(cards, overworld, liveDraft)
  return (
    <div className="flex flex-col gap-4">
      <AreaEditorPanel
        cards={cards}
        disabled={!document.writable || save.isPending}
        liveDraft={liveDraft}
        onDraftChange={setLiveDraft}
        onSave={(world, shapes, done) =>
          save.mutate(
            {
              revision: document.revision,
              changes: { [worlds.path]: withShapes(cards, worlds.template ?? [], world, shapes) },
            },
            {
              onSuccess: (saved) => {
                setDraftValue(file, worlds.path, undefined)
                announceSave(t("steward.game.areas-saved"), saved.reload)
                done()
              },
            },
          )
        }
      />
      {warnings.length > 0 ? (
        <ul className="flex flex-col gap-1 text-sm text-warning">
          {warnings.map((warning) => (
            <li key={warning}>{warning}</li>
          ))}
        </ul>
      ) : null}
      <DraftForm file={file} document={document} target={target} save={save} />
    </div>
  )
}

function AreaEditorPanel({
  cards,
  disabled,
  liveDraft,
  onDraftChange,
  onSave,
}: {
  cards: SectionValues[]
  disabled: boolean
  liveDraft: LiveDraft | null
  onDraftChange: (draft: LiveDraft | null) => void
  onSave: (world: string, shapes: AreaShape[], done: () => void) => void
}) {
  const client = useQueryClient()
  const taker = useQuery({
    queryKey: keys.positionTaker,
    queryFn: () => api<PositionTaker>("/api/smp/take-position"),
    refetchInterval: RECONCILE,
    refetchOnMount: "always",
    refetchOnWindowFocus: "always",
  })
  const names = cards.map((card) => text(card, "world")).filter((name) => name !== "")
  const [chosen, choose] = useState<string | undefined>(undefined)
  const editing = liveDraft !== null
  const selected = liveDraft?.world ?? chosen
  const world = selected !== undefined && names.includes(selected) ? selected : names[0]
  const [busy, setBusy] = useState(false)
  const [name, setName] = useState("")
  const probed = useRef(false)

  const syncDraft = useCallback(async () => {
    try {
      const done = await ask(client, "/api/smp/area-draft", { step: "READ" })
      if (done.status === "DONE") {
        const answer = shapesOf(done)
        if (answer !== undefined) onDraftChange(answer)
      } else if (done.status === "REFUSED" && done.reason === "NO_AREA_DRAFT") {
        onDraftChange(null)
      }
    } catch {
      // Silent background probe: leave the current editing state untouched on transient errors.
    }
  }, [client, onDraftChange])

  useEffect(() => {
    if (disabled || taker.data?.taker !== "READY" || probed.current) return
    probed.current = true
    void syncDraft()
  }, [disabled, taker.data?.taker, syncDraft])

  useEffect(() => {
    if (!editing || disabled) return undefined
    const onFocus = () => void syncDraft()
    window.addEventListener("focus", onFocus)
    return () => window.removeEventListener("focus", onFocus)
  }, [editing, disabled, syncDraft])

  /** Runs one request in the game and answers its run once it is final, or nothing when it failed. */
  const run = async (path: string, body: unknown): Promise<CommandRun | undefined> => {
    setBusy(true)
    try {
      const done = await ask(client, path, body)
      if (done.status === "DONE") return done
      if (done.status === "REFUSED" && done.reason === "NO_AREA_DRAFT") onDraftChange(null)
      void client.invalidateQueries({ queryKey: keys.positionTaker })
      toast.error(t("steward.game.area-draft-failed"), { description: done.result ? message(done.result) : undefined })
    } catch (error) {
      toast.error(t("steward.game.area-draft-failed"), { description: errorMessage(error) })
    } finally {
      setBusy(false)
    }
    return undefined
  }

  const edit = async () => {
    if (world === undefined) return
    const shapes = shapesFrom(cards.find((card) => text(card, "world") === world))
    if (await run("/api/smp/area-editor", { world, heights: false, shapes })) onDraftChange({ world, shapes })
  }

  const add = async () => {
    const done = await run("/api/smp/area-draft", { step: "ADD", name: name.trim() })
    if (done) {
      const answer = shapesOf(done)
      if (answer !== undefined) onDraftChange(answer)
      setName("")
    }
  }

  const saveDraft = async () => {
    const read = await run("/api/smp/area-draft", { step: "READ" })
    const answer = read ? shapesOf(read) : undefined
    if (answer === undefined) return
    onDraftChange(answer)
    onSave(answer.world, answer.shapes, () => {
      void run("/api/smp/area-draft", { step: "CLOSE" }).then(() => onDraftChange(null))
    })
  }

  const discard = async () => {
    if (await run("/api/smp/area-draft", { step: "CLOSE" })) onDraftChange(null)
  }

  const blocked = blockedBy(taker.data)
  const off = disabled || busy || blocked !== null
  return (
    <section className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <Select value={world ?? ""} onValueChange={choose} disabled={off || editing || names.length === 0}>
          <SelectTrigger aria-label={t("steward.game.area-world")} className="max-sm:w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {names.map((entry) => (
              <SelectItem key={entry} value={entry}>
                {entry}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Button
          type="button"
          variant="outline"
          className="max-sm:w-full"
          disabled={off || world === undefined}
          onClick={() => void edit()}
        >
          <CursorClickIcon aria-hidden />
          {t("steward.game.edit-areas")}
        </Button>
      </div>
      {editing ? (
        <>
          <p className="text-xs text-muted-foreground">{t("steward.game.editing-areas")}</p>
          <div className="flex flex-wrap items-center gap-2">
            <Input
              aria-label={t("steward.game.polygon-name")}
              placeholder={t("steward.game.polygon-name")}
              value={name}
              disabled={off}
              className="w-auto max-sm:w-full"
              onChange={(event) => setName(event.target.value)}
            />
            <Button
              type="button"
              variant="outline"
              className="max-sm:w-full"
              disabled={off || name.trim() === ""}
              onClick={() => void add()}
            >
              <PlusIcon aria-hidden />
              {t("steward.game.new-polygon")}
            </Button>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              variant="secondary"
              className="max-sm:w-full"
              disabled={off}
              onClick={() => void discard()}
            >
              {t("steward.game.discard-areas")}
            </Button>
            <Button type="button" className="max-sm:w-full" disabled={off} onClick={() => void saveDraft()}>
              {t("steward.game.save-areas")}
            </Button>
          </div>
        </>
      ) : null}
      {blocked !== null && !disabled ? <p className="text-xs text-muted-foreground">{blocked}</p> : null}
    </section>
  )
}
