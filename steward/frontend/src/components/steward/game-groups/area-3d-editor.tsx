import { useState } from "react"

import type { ConfigDocument, ConfigEntry } from "@/lib/api"
import { announceSave } from "@/lib/announce-save"
import { setDraftValue, useDraft } from "@/lib/drafts"
import { useConfig, useSaveConfig } from "@/lib/queries"
import { t } from "@/lib/texts"
import { type Draft, draftSections } from "@/components/steward/configuration"
import type { CustomEditorProps } from "@/components/steward/config-editors"
import type { SectionValues } from "@/components/steward/repeatable-cards"
import { DraftForm } from "@/components/steward/settings-config"
import { type DraftValue, isDraftValueRecord } from "@/components/steward/group-draft"
import { AreaEditorPanel, type AreaShape, type LiveDraft } from "@/components/steward/game-groups/border-editor"
import { text } from "@/components/steward/game-groups/model"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"

const REQUIRED_3D_KEYS = ["world", "min-y", "max-y", "corners"]

function is3dAreaEntry(entry: ConfigEntry | undefined): entry is ConfigEntry {
  if (entry?.kind !== "SECTIONS" || entry.template === undefined) return false
  const keys = new Set(entry.template.map((field) => field.key))
  return (
    REQUIRED_3D_KEYS.every((key) => keys.has(key)) &&
    entry.template.find((field) => field.key === "corners")?.kind === "SECTIONS"
  )
}

export function readsSpawn(document: ConfigDocument): boolean {
  return is3dAreaEntry(document.entries.find((entry) => entry.path === "protected-areas"))
}

export function readsDuels(document: ConfigDocument): boolean {
  return (
    is3dAreaEntry(document.entries.find((entry) => entry.path === "negotiation-area")) &&
    is3dAreaEntry(document.entries.find((entry) => entry.path === "arena"))
  )
}

function whole(value: string): number {
  const parsed = Number(value)
  return value !== "" && Number.isInteger(parsed) ? parsed : 0
}

/** Reads the 3D polygon entries of `world` into the shapes the in-game area editor starts with. */
export function shapesFrom3d(entries: SectionValues[], world: string): AreaShape[] {
  return entries
    .filter((entry) => text(entry, "world") === world)
    .map((entry) => ({
      name: text(entry, "name"),
      corners: (Array.isArray(entry.corners) ? entry.corners : []).flatMap((corner) => {
        if (typeof corner === "string") return []
        const x = Number(text(corner, "x"))
        const z = Number(text(corner, "z"))
        return text(corner, "x") === "" || text(corner, "z") === "" || !Number.isInteger(x) || !Number.isInteger(z)
          ? []
          : [{ x, z }]
      }),
      bottom: whole(text(entry, "min-y")),
      top: whole(text(entry, "max-y")),
    }))
}

/** Replaces the 3D polygon entries of `world` with `shapes`, keeping entries of other worlds unchanged. */
export function withShapes3d(entries: SectionValues[], world: string, shapes: AreaShape[]): SectionValues[] {
  const kept = entries.filter((entry) => text(entry, "world") !== world)
  const replaced: SectionValues[] = shapes.map((shape) => ({
    name: shape.name,
    world,
    "min-y": String(shape.bottom),
    "max-y": String(shape.top),
    corners: shape.corners.map((corner) => ({ x: String(corner.x), z: String(corner.z) })),
  }))
  return [...kept, ...replaced]
}

function availableWorlds(entries: SectionValues[], configuredWorlds: ConfigEntry[] | undefined): string[] {
  const seen = new Set<string>()
  for (const entry of entries) {
    const world = text(entry, "world")
    if (world !== "") seen.add(world)
  }
  for (const entry of configuredWorlds ?? []) {
    if (entry.path.startsWith("world-") && typeof entry.value === "string" && entry.value !== "") {
      seen.add(entry.value)
    }
  }
  if (seen.size === 0) seen.add("nordtal")
  return [...seen]
}

function Area3dGroupEditor({ file, document, target, paths }: CustomEditorProps & { paths: readonly string[] }) {
  const draft = useDraft<DraftValue>(file, isDraftValueRecord) as Draft
  const worldsDoc = useConfig(`${document.service}/worlds`).data
  const save = useSaveConfig(document.path)
  const [liveDraft, setLiveDraft] = useState<LiveDraft | null>(null)
  const [activePath, setActivePath] = useState<string>(paths[0] ?? "")

  const entries = paths
    .map((path) => document.entries.find((entry) => entry.path === path))
    .filter((entry): entry is ConfigEntry => entry !== undefined)
  const selected = entries.find((entry) => entry.path === activePath) ?? entries[0]
  if (selected === undefined) return null

  const sections = draftSections(draft, selected)
  const worlds = availableWorlds(sections, worldsDoc?.entries)

  const beforeWorld =
    entries.length > 1 ? (
      <Select
        value={selected.path}
        onValueChange={setActivePath}
        disabled={!document.writable || save.isPending || liveDraft !== null}
      >
        <SelectTrigger aria-label={t("steward.game.area")} className="max-sm:w-full">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {entries.map((entry) => (
            <SelectItem key={entry.path} value={entry.path}>
              {entry.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    ) : undefined

  return (
    <div className="flex flex-col gap-4">
      <AreaEditorPanel
        worlds={worlds}
        shapesForWorld={(world) => shapesFrom3d(sections, world)}
        heights
        beforeWorld={beforeWorld}
        disabled={!document.writable || save.isPending}
        liveDraft={liveDraft}
        onDraftChange={setLiveDraft}
        onSave={(world, shapes, done) =>
          save.mutate(
            {
              revision: document.revision,
              changes: { [selected.path]: withShapes3d(sections, world, shapes) },
            },
            {
              onSuccess: (saved) => {
                setDraftValue(file, selected.path, undefined)
                announceSave(t("steward.game.areas-saved"), saved.reload)
                done()
              },
            },
          )
        }
      />
      <DraftForm file={file} document={document} target={target} save={save} />
    </div>
  )
}

export function SpawnEditor(props: CustomEditorProps) {
  return <Area3dGroupEditor {...props} paths={["protected-areas"]} />
}

export function DuelsEditor(props: CustomEditorProps) {
  return <Area3dGroupEditor {...props} paths={["negotiation-area", "arena"]} />
}
