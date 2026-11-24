import { XIcon } from "@/components/ui/icons"
import { useQueryClient } from "@tanstack/react-query"
import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { toast } from "sonner"

import {
  api,
  errorMessage,
  type CommandAsked,
  type CommandRun,
  type ConfigDocument,
  type ConfigEntry,
  type SpawnExceptionBlock,
} from "@/lib/api"
import { announceSave } from "@/lib/announce-save"
import { finalRun } from "@/lib/queries-commands"
import { keys } from "@/lib/query-keys"
import { useSaveConfig } from "@/lib/queries"
import { message, t } from "@/lib/texts"
import { draftSections } from "@/components/steward/configuration"
import type { CustomEditorProps } from "@/components/steward/config-editors"
import type { SectionValues } from "@/components/steward/repeatable-cards"
import { SaveDraft, noticeOf, useGroupDraft } from "@/components/steward/group-draft"
import {
  CaptureBlockedNote,
  CaptureSessionControls,
  CaptureStartButton,
  captureBlockedBy,
  useCaptureReadiness,
} from "@/components/steward/game-groups/in-game-capture"
import { text } from "@/components/steward/game-groups/model"
import { Empty, Failure } from "@/components/steward/query-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"

const REQUIRED_KEYS = ["world", "x", "y", "z", "kind", "facing", "default-state", "reset-mode", "icon-side"]

export function readsSpawnExceptions(document: ConfigDocument): boolean {
  const entry = document.entries.find((item) => item.path === "entries")
  if (entry?.kind !== "SECTIONS" || entry.template === undefined) return false
  const fieldKeys = new Set(entry.template.map((field) => field.key))
  return REQUIRED_KEYS.every((key) => fieldKeys.has(key))
}

function posKey(world: string, x: number | string, y: number | string, z: number | string): string {
  return `${world}:${String(x)},${String(y)},${String(z)}`
}

function isSpawnExceptionBlock(value: unknown): value is SpawnExceptionBlock {
  return (
    typeof value === "object" &&
    value !== null &&
    "world" in value &&
    typeof value.world === "string" &&
    "x" in value &&
    typeof value.x === "number" &&
    "y" in value &&
    typeof value.y === "number" &&
    "z" in value &&
    typeof value.z === "number" &&
    "kind" in value &&
    (value.kind === "DOOR" || value.kind === "TRAPDOOR") &&
    "facing" in value &&
    (value.facing === "NORTH" || value.facing === "SOUTH" || value.facing === "EAST" || value.facing === "WEST") &&
    "defaultOpen" in value &&
    typeof value.defaultOpen === "boolean"
  )
}

/** Reads the blocks answered by a done spawn exception draft step. */
export function blocksOf(run: CommandRun): SpawnExceptionBlock[] | undefined {
  const entries = run.answer?.entries
  if (!Array.isArray(entries) || !entries.every(isSpawnExceptionBlock)) return undefined
  return entries
}

/** Converts section values into the capture request payload. */
export function blocksFromSections(sections: SectionValues[]): SpawnExceptionBlock[] {
  return sections.map((entry) => ({
    world: text(entry, "world"),
    x: Number(text(entry, "x")) || 0,
    y: Number(text(entry, "y")) || 0,
    z: Number(text(entry, "z")) || 0,
    kind: text(entry, "kind") === "TRAPDOOR" ? "TRAPDOOR" : "DOOR",
    facing: toFacing(text(entry, "facing")),
    defaultOpen: text(entry, "default-state") === "OPEN",
  }))
}

function toFacing(value: string): SpawnExceptionBlock["facing"] {
  if (value === "SOUTH" || value === "EAST" || value === "WEST") return value
  return "NORTH"
}

/** Merges captured blocks with existing section values, preserving labels, reset settings and icon side. */
export function mergeCaptured(existing: SectionValues[], captured: SpawnExceptionBlock[]): SectionValues[] {
  const byPos = new Map(
    existing.map((entry) => [
      posKey(text(entry, "world"), text(entry, "x"), text(entry, "y"), text(entry, "z")),
      entry,
    ]),
  )
  return captured.map((block): SectionValues => {
    const key = posKey(block.world, block.x, block.y, block.z)
    const prior = byPos.get(key)
    if (prior !== undefined) {
      return {
        ...prior,
        kind: block.kind,
        facing: block.facing,
      }
    }
    return {
      world: block.world,
      x: String(block.x),
      y: String(block.y),
      z: String(block.z),
      label: "",
      kind: block.kind,
      facing: block.facing,
      "default-state": block.defaultOpen ? "OPEN" : "CLOSED",
      "reset-mode": "STAY",
      "reset-minutes": "0",
      "reset-seconds": "0",
      "icon-side": "BOTH",
    }
  })
}

/** Returns the human-readable labels for FRONT and BACK icon sides in the block's default state. */
export function iconSideLabels(kind: string, facing: string, defaultState: string): { front: string; back: string } {
  const defaultOpen = defaultState === "OPEN"
  const northSouthFacing = facing === "NORTH" || facing === "SOUTH"
  if (kind === "TRAPDOOR") {
    if (!defaultOpen) {
      return { front: t("steward.game.side-top"), back: t("steward.game.side-bottom") }
    }
    return northSouthFacing
      ? { front: t("steward.game.side-north"), back: t("steward.game.side-south") }
      : { front: t("steward.game.side-east"), back: t("steward.game.side-west") }
  }
  const northSouthDefault = defaultOpen !== northSouthFacing
  return northSouthDefault
    ? { front: t("steward.game.side-north"), back: t("steward.game.side-south") }
    : { front: t("steward.game.side-east"), back: t("steward.game.side-west") }
}

async function ask(client: ReturnType<typeof useQueryClient>, path: string, body: unknown): Promise<CommandRun> {
  const asked = await api<CommandAsked>(path, { method: "POST", body })
  return finalRun(client, asked.id)
}

/** Dedicated SMP settings page for spawn door and trapdoor exceptions with unified in-game capture. */
export function SpawnExceptionsEditor({ file, document }: CustomEditorProps) {
  const client = useQueryClient()
  const entry: ConfigEntry | undefined = document.entries.find((item) => item.path === "entries")
  const mutation = useSaveConfig(document.path)
  const { draft, count, invalid, set, submit, discard, save } = useGroupDraft(file, document, mutation)
  const readiness = useCaptureReadiness()
  const [capturing, setCapturing] = useState(false)
  const [busy, setBusy] = useState(false)
  const probed = useRef(false)

  const sections = useMemo(() => (entry ? draftSections(draft, entry) : []), [draft, entry])
  const sectionsRef = useRef(sections)
  useEffect(() => {
    sectionsRef.current = sections
  }, [sections])

  const syncDraft = useCallback(async () => {
    try {
      const done = await ask(client, "/api/smp/spawn-exceptions-draft", { step: "READ" })
      if (done.status === "DONE") {
        const blocks = blocksOf(done)
        if (blocks !== undefined) {
          setCapturing(true)
          set("entries", mergeCaptured(sectionsRef.current, blocks))
        }
      } else if (done.status === "REFUSED" && done.reason === "NO_SPAWN_EXCEPTION_DRAFT") {
        setCapturing(false)
      }
    } catch {
      // Silent background probe: leave current state untouched on transient errors.
    }
  }, [client, set, setCapturing])

  useEffect(() => {
    if (!document.writable || readiness.data?.taker !== "READY" || probed.current) return
    probed.current = true
    void syncDraft()
  }, [document.writable, readiness.data?.taker, syncDraft])

  useEffect(() => {
    if (!capturing || !document.writable) return undefined
    const onFocus = () => void syncDraft()
    window.addEventListener("focus", onFocus)
    return () => window.removeEventListener("focus", onFocus)
  }, [capturing, document.writable, syncDraft])

  if (entry === undefined) return null

  const run = async (path: string, body: unknown): Promise<CommandRun | undefined> => {
    setBusy(true)
    try {
      const done = await ask(client, path, body)
      if (done.status === "DONE") return done
      if (done.status === "REFUSED" && done.reason === "NO_SPAWN_EXCEPTION_DRAFT") setCapturing(false)
      void client.invalidateQueries({ queryKey: keys.captureReadiness })
      toast.error(t("steward.game.spawn-exceptions-capture-failed"), {
        description: done.result ? message(done.result) : undefined,
      })
    } catch (error) {
      toast.error(t("steward.game.spawn-exceptions-capture-failed"), { description: errorMessage(error) })
    } finally {
      setBusy(false)
    }
    return undefined
  }

  const startCapture = async () => {
    if (await run("/api/smp/spawn-exceptions-editor", { entries: blocksFromSections(sections) })) {
      setCapturing(true)
    }
  }

  const doneCapture = async () => {
    const read = await run("/api/smp/spawn-exceptions-draft", { step: "READ" })
    const blocks = read ? blocksOf(read) : undefined
    if (blocks === undefined) return
    const merged = mergeCaptured(sections, blocks)
    set("entries", merged)
    mutation.mutate(
      { revision: document.revision, changes: { entries: merged } },
      {
        onSuccess: (saved) => {
          set("entries", undefined)
          announceSave(t("steward.game.spawn-exceptions-saved"), saved.reload)
          void run("/api/smp/spawn-exceptions-draft", { step: "CLOSE" }).then(() => setCapturing(false))
        },
      },
    )
  }

  const cancelCapture = async () => {
    if (await run("/api/smp/spawn-exceptions-draft", { step: "CLOSE" })) {
      setCapturing(false)
      discard()
    }
  }

  const updateRow = (index: number, patch: Record<string, string>) => {
    set(
      "entries",
      sections.map((row, i): SectionValues => (i === index ? { ...row, ...patch } : row)),
    )
  }

  const removeRow = (index: number) => {
    set(
      "entries",
      sections.filter((_, i) => i !== index),
    )
  }

  const blocked = captureBlockedBy(readiness.data)
  const off = !document.writable || save.isPending || busy || blocked !== null
  const notice = noticeOf(document)

  return (
    <div className="flex flex-col gap-4">
      {notice !== null ? <p className="text-xs text-muted-foreground">{notice}</p> : null}
      {save.error ? <Failure error={save.error} write /> : null}

      <section className="flex flex-col gap-2">
        {!capturing ? (
          <div className="flex flex-wrap items-center gap-2">
            <CaptureStartButton
              label={t("steward.game.select-spawn-exceptions")}
              disabled={off}
              onStart={() => void startCapture()}
            />
          </div>
        ) : (
          <CaptureSessionControls
            notice={t("steward.game.selecting-spawn-exceptions")}
            disabled={off}
            cancelLabel={t("steward.game.discard-areas")}
            doneLabel={t("steward.game.save-areas")}
            onCancel={() => void cancelCapture()}
            onDone={() => void doneCapture()}
          />
        )}
        <CaptureBlockedNote blocked={blocked} disabled={!document.writable} />
      </section>

      {sections.length === 0 ? (
        <Empty title={t("steward.game.no-spawn-exceptions")} />
      ) : (
        <div className="flex flex-col gap-3">
          {sections.map((row, index) => {
            const world = text(row, "world")
            const x = text(row, "x")
            const y = text(row, "y")
            const z = text(row, "z")
            const kind = text(row, "kind") || "DOOR"
            const facing = text(row, "facing") || "NORTH"
            const defaultState = text(row, "default-state") || "CLOSED"
            const resetMode = text(row, "reset-mode") || "STAY"
            const iconSide = text(row, "icon-side") || "BOTH"
            const sides = iconSideLabels(kind, facing, defaultState)
            const key = `${posKey(world, x, y, z)}-${String(index)}`

            return (
              <Card key={key} className="flex flex-col gap-3 p-4">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge variant="secondary">
                      {kind === "TRAPDOOR" ? t("steward.game.trapdoor") : t("steward.game.door")}
                    </Badge>
                    <span className="font-mono text-sm">
                      {world} {x} {y} {z}
                    </span>
                  </div>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    disabled={!document.writable}
                    aria-label={t("steward.game.remove-it")}
                    onClick={() => removeRow(index)}
                  >
                    <XIcon aria-hidden />
                  </Button>
                </div>

                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor={`${key}-label`}>{t("steward.game.label")}</Label>
                    <Input
                      id={`${key}-label`}
                      value={text(row, "label")}
                      placeholder={t("steward.game.label-placeholder")}
                      disabled={!document.writable}
                      onChange={(event) => updateRow(index, { label: event.target.value })}
                    />
                  </div>

                  <div className="flex flex-col gap-1.5">
                    <Label>{t("steward.game.default-state")}</Label>
                    <Select
                      value={defaultState}
                      disabled={!document.writable}
                      onValueChange={(value) => updateRow(index, { "default-state": value })}
                    >
                      <SelectTrigger className="w-full">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="CLOSED">{t("steward.game.closed")}</SelectItem>
                        <SelectItem value="OPEN">{t("steward.game.open")}</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>

                  <div className="flex flex-col gap-1.5">
                    <Label>{t("steward.game.icon-side")}</Label>
                    <Select
                      value={iconSide}
                      disabled={!document.writable}
                      onValueChange={(value) => updateRow(index, { "icon-side": value })}
                    >
                      <SelectTrigger className="w-full">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="BOTH">{t("steward.game.side-both")}</SelectItem>
                        <SelectItem value="FRONT">{sides.front}</SelectItem>
                        <SelectItem value="BACK">{sides.back}</SelectItem>
                        <SelectItem value="NONE">{t("steward.game.side-none")}</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>

                  <div className="flex flex-col gap-1.5">
                    <Label>{t("steward.game.reset-behaviour")}</Label>
                    <Select
                      value={resetMode}
                      disabled={!document.writable}
                      onValueChange={(value) => updateRow(index, { "reset-mode": value })}
                    >
                      <SelectTrigger className="w-full">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="STAY">{t("steward.game.stay-in-new-state")}</SelectItem>
                        <SelectItem value="AUTO_RESET">{t("steward.game.auto-reset")}</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                </div>

                {resetMode === "AUTO_RESET" ? (
                  <div className="grid grid-cols-2 gap-3">
                    <div className="flex flex-col gap-1.5">
                      <Label htmlFor={`${key}-min`}>{t("steward.game.minutes")}</Label>
                      <Input
                        id={`${key}-min`}
                        type="number"
                        min={0}
                        value={text(row, "reset-minutes") || "0"}
                        disabled={!document.writable}
                        onChange={(event) => updateRow(index, { "reset-minutes": event.target.value })}
                      />
                    </div>
                    <div className="flex flex-col gap-1.5">
                      <Label htmlFor={`${key}-sec`}>{t("steward.game.seconds")}</Label>
                      <Input
                        id={`${key}-sec`}
                        type="number"
                        min={0}
                        max={59}
                        value={text(row, "reset-seconds") || "0"}
                        disabled={!document.writable}
                        onChange={(event) => updateRow(index, { "reset-seconds": event.target.value })}
                      />
                    </div>
                  </div>
                ) : null}
              </Card>
            )
          })}
        </div>
      )}

      {!capturing ? (
        <SaveDraft
          count={count}
          invalid={invalid}
          writable={document.writable}
          pending={save.isPending}
          onDiscard={discard}
          onSave={submit}
        />
      ) : null}
    </div>
  )
}
