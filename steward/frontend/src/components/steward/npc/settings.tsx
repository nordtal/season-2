import { CopyIcon, TrashIcon, UploadSimpleIcon, XIcon } from "@/components/ui/icons"
import { useId, useRef, useState, type ReactNode } from "react"
import { toast } from "sonner"

import { errorMessage, type Npc, type NpcPlace, type NpcSkinUploaded, type Pose, type Slot } from "@/lib/api"
import { type Language, LANGUAGES, LONGEST_NAME } from "@/lib/npc"
import { SKIN_SIDE, readSkin } from "@/lib/npc-skin"
import { measureKey, useCopySkin, useUploadSkin } from "@/lib/queries-npcs"
import { useGameData } from "@/lib/queries-settings"
import { gameKey, nameOfId } from "@/lib/references"
import { choice, t } from "@/lib/texts"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { GameIcon } from "@/components/steward/game-icon"
import { TakeInButton } from "@/components/steward/game-groups/take-in"
import { TakePosition, type TakenPosition } from "@/components/steward/game-groups/take-position"
import { FigureCard } from "@/components/steward/npc/preview"
import { Heading, Room, type Measured } from "@/components/steward/npc/step-panel"
import { Hint } from "@/components/steward/status"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"

/** Everything of an NPC but its dialogue: its name, how it stands and sounds, where it stands, what it wears and its skin. */

type Edit = (change: (npc: Npc) => Npc) => void

const POSES: readonly Pose[] = ["STAND", "SIT", "CROUCH"]
const SLOTS: readonly Slot[] = ["HEAD", "CHEST", "LEGS", "FEET", "HAND", "OFF_HAND"]
const PITCH = { min: 0.5, max: 2, step: 0.05 }

function Field({ id, label, children }: { id?: string; label: string; children: ReactNode }) {
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <Label htmlFor={id} className="text-xs font-normal text-muted-foreground">
        {label}
      </Label>
      {children}
    </div>
  )
}

function Name({ npc, language, measured, edit }: { npc: Npc; language: Language; measured: Measured; edit: Edit }) {
  const id = useId()
  const text = npc.name[language]
  const measurement = text === "" ? undefined : measured?.get(measureKey({ text, kind: "NAME", answers: 0 }))
  return (
    <Field id={id} label={t("steward.npcs.name-in", { language })}>
      <Input
        id={id}
        lang={language}
        value={text}
        maxLength={LONGEST_NAME}
        onChange={(event) => edit((draft) => ({ ...draft, name: { ...draft.name, [language]: event.target.value } }))}
      />
      <Room measurement={measurement} />
    </Field>
  )
}

/** A number as it is typed. The NPC takes it once it is one, and a value from elsewhere, such as a taken position, replaces what was typed. */
function NumberField({ label, value, onChange }: { label: string; value: number; onChange: (value: number) => void }) {
  const id = useId()
  const [typed, setTyped] = useState(String(value))
  const [from, setFrom] = useState(value)
  if (from !== value) {
    setFrom(value)
    if (Number(typed) !== value) setTyped(String(value))
  }
  return (
    <Field id={id} label={label}>
      <Input
        id={id}
        inputMode="decimal"
        value={typed}
        aria-invalid={typed.trim() === "" || !Number.isFinite(Number(typed))}
        className="font-mono tabular-nums"
        onChange={(event) => {
          const text = event.target.value.replace(",", ".")
          if (!/^-?\d*\.?\d*$/.test(text)) return
          setTyped(text)
          const number = Number(text)
          if (text.trim() !== "" && Number.isFinite(number)) onChange(number)
        }}
      />
    </Field>
  )
}

/** A facing as the NPC stores it: from 180 degrees left to 180 right, in tenths. */
function facing(yaw: number): number {
  const turned = ((((yaw + 180) % 360) + 360) % 360) - 180
  return Math.round(turned * 10) / 10
}

function hundredth(value: number): number {
  return Math.round(value * 100) / 100
}

/** Where the admin's player stands and faces, as the NPC's place. */
export function placeOf(taken: TakenPosition): NpcPlace {
  return {
    world: taken.world,
    x: hundredth(taken.x),
    y: hundredth(taken.y),
    z: hundredth(taken.z),
    yaw: facing(taken.yaw),
  }
}

function Place({ npc, edit }: { npc: Npc; edit: Edit }) {
  const world = useId()
  const set = (change: Partial<NpcPlace>) => edit((draft) => ({ ...draft, place: { ...draft.place, ...change } }))
  return (
    <section className="flex flex-col gap-3">
      <Heading>{t("steward.npcs.place")}</Heading>
      {npc.place.world.trim() === "" ? (
        <p className="text-sm text-muted-foreground">{t("steward.npcs.no-place")}</p>
      ) : null}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-5">
        <div className="col-span-2">
          <Field id={world} label={t("steward.npcs.world")}>
            <Input id={world} value={npc.place.world} onChange={(event) => set({ world: event.target.value })} />
          </Field>
        </div>
        <NumberField label="X" value={npc.place.x} onChange={(x) => set({ x })} />
        <NumberField label="Y" value={npc.place.y} onChange={(y) => set({ y })} />
        <NumberField label="Z" value={npc.place.z} onChange={(z) => set({ z })} />
        <NumberField
          label={t("steward.npcs.facing")}
          value={npc.place.yaw}
          onChange={(yaw) => set({ yaw: facing(yaw) })}
        />
      </div>
      <TakePosition shape="spot" disabled={false} onTaken={(taken) => set(placeOf(taken))} />
    </section>
  )
}

function Items({ npc, edit }: { npc: Npc; edit: Edit }) {
  const game = useGameData()
  const names = game.data?.registries.item ?? []
  return (
    <section className="flex flex-col gap-3">
      <Heading>{t("steward.npcs.items")}</Heading>
      <ul className="grid grid-cols-1 gap-2 sm:grid-cols-2">
        {SLOTS.map((slot) => {
          const held = npc.equipment[slot]
          const key = held === undefined ? undefined : gameKey(held.item)
          return (
            <li key={slot} className="flex min-w-0 flex-col gap-2 rounded-lg border p-3">
              <div className="flex min-w-0 items-center gap-2">
                <GameIcon icons={game.data?.icons} item={key} size={32} />
                <div className="flex min-w-0 flex-1 flex-col">
                  <span className="text-xs text-muted-foreground">
                    {t("steward.npcs.slot", { slot: choice(slot) })}
                  </span>
                  <span className="truncate text-sm">
                    {key === undefined
                      ? t("steward.npcs.empty-slot")
                      : (names.find((entry) => entry.id === key)?.text ?? nameOfId(key))}
                  </span>
                </div>
                {held === undefined ? null : (
                  <Hint tip={t("steward.npcs.remove-item", { slot: choice(slot) })}>
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon"
                      className="tap-target shrink-0"
                      aria-label={t("steward.npcs.remove-item", { slot: choice(slot) })}
                      onClick={() =>
                        edit((draft) => {
                          const { [slot]: _removed, ...equipment } = draft.equipment
                          return { ...draft, equipment }
                        })
                      }
                    >
                      <XIcon aria-hidden />
                    </Button>
                  </Hint>
                )}
              </div>
              <TakeInButton
                purpose="NPC_ITEM"
                disabled={false}
                onTaken={(taken) => edit((draft) => ({ ...draft, equipment: { ...draft.equipment, [slot]: taken } }))}
              />
            </li>
          )
        })}
      </ul>
    </section>
  )
}

/** Shows why the server refused a skin, which its sentence says in full. */
function refused(error: unknown) {
  toast.error(errorMessage(error))
}

function Skin({ npc, published, edit }: { npc: Npc; published: Npc | undefined; edit: Edit }) {
  const upload = useUploadSkin()
  const copy = useCopySkin()
  const file = useRef<HTMLInputElement>(null)
  const player = useId()
  const [name, setName] = useState("")
  const busy = upload.isPending || copy.isPending
  const wear = (skin: NpcSkinUploaded) => edit((draft) => ({ ...draft, skin: skin.id, skinModel: skin.model }))
  const chosen = async (picked: File) => {
    let read
    try {
      read = await readSkin(picked)
    } catch {
      toast.error(t("steward.npcs.skin-not-saved"), { description: t("steward.npcs.skin-unreadable") })
      return
    }
    if (read.width !== SKIN_SIDE || read.height !== SKIN_SIDE) {
      toast.error(t("steward.npcs.skin-not-saved"), {
        description: t("steward.npcs.skin-size", { width: read.width, height: read.height }),
      })
      return
    }
    // The promise, not the hook's callbacks, takes the answer: it arrives even after the admin left this tab.
    upload.mutateAsync({ file: new Blob([picked], { type: "image/png" }), slim: read.slim }).then(wear, refused)
  }

  return (
    <section className="flex flex-col gap-3">
      <Heading>{t("steward.npcs.skin")}</Heading>
      <div className="flex items-start gap-4">
        <FigureCard npc={npc} className="h-40 shrink-0 rounded-lg bg-card p-2" />
        <div className="flex min-w-0 flex-1 flex-col gap-3">
          <p className="text-sm">
            {npc.skin === undefined
              ? t("steward.npcs.default-skin")
              : t("steward.npcs.skin-model", { model: choice(npc.skinModel) })}
          </p>
          <div className="flex flex-wrap gap-2">
            <input
              ref={file}
              type="file"
              accept="image/png"
              className="sr-only"
              tabIndex={-1}
              aria-hidden
              onChange={(event) => {
                const picked = event.target.files?.[0]
                event.target.value = ""
                if (picked) void chosen(picked)
              }}
            />
            <Button
              type="button"
              variant="outline"
              className="max-sm:w-full"
              disabled={busy}
              onClick={() => file.current?.click()}
            >
              <UploadSimpleIcon aria-hidden />
              {t("steward.npcs.upload-skin")}
            </Button>
            {npc.skin === undefined ? null : (
              <Button
                type="button"
                variant="ghost"
                className="max-sm:w-full"
                disabled={busy}
                onClick={() =>
                  edit((draft) => {
                    const { skin: _skin, ...rest } = draft
                    return { ...rest, skinModel: "CLASSIC" }
                  })
                }
              >
                {t("steward.npcs.use-default-skin")}
              </Button>
            )}
          </div>
          <form
            className="flex flex-wrap items-end gap-2"
            onSubmit={(event) => {
              event.preventDefault()
              if (name.trim() === "") return
              copy.mutateAsync(name.trim()).then((skin) => {
                wear(skin)
                setName("")
              }, refused)
            }}
          >
            <div className="min-w-40 flex-1">
              <Field id={player} label={t("steward.npcs.copy-from")}>
                <Input id={player} value={name} autoComplete="off" onChange={(event) => setName(event.target.value)} />
              </Field>
            </div>
            <Button type="submit" variant="outline" className="max-sm:w-full" disabled={busy || name.trim() === ""}>
              <CopyIcon aria-hidden />
              {t("steward.npcs.copy-skin")}
            </Button>
          </form>
        </div>
      </div>
      {npc.skin !== undefined && npc.skin !== published?.skin ? (
        <p className="text-xs text-muted-foreground">{t("steward.npcs.skin-pending")}</p>
      ) : null}
    </section>
  )
}

/** The pitch as a slider, which a number beside it reads out. */
function Pitch({ npc, edit }: { npc: Npc; edit: Edit }) {
  const id = useId()
  return (
    <Field id={id} label={t("steward.npcs.pitch")}>
      <div className="flex h-8 items-center gap-3 pointer-coarse:min-h-control">
        <input
          id={id}
          type="range"
          min={PITCH.min}
          max={PITCH.max}
          step={PITCH.step}
          value={npc.pitch}
          className="min-w-0 flex-1 accent-primary"
          onChange={(event) => {
            const pitch = Number(event.target.value)
            edit((draft) => ({ ...draft, pitch }))
          }}
        />
        <span className="w-10 text-right font-mono text-sm tabular-nums">{npc.pitch.toFixed(2)}</span>
      </div>
    </Field>
  )
}

export function NpcSettings({
  npc,
  published,
  measured,
  edit,
  onDelete,
}: {
  npc: Npc
  /** The NPC as the game has it, or nothing for one that was never published. */
  published: Npc | undefined
  measured: Measured
  edit: Edit
  onDelete: () => Promise<unknown>
}) {
  const pose = useId()
  const name = npc.name.en.trim() || published?.name.en || t("steward.npcs.unnamed")
  return (
    <div className="grid grid-cols-1 gap-8 lg:grid-cols-2">
      <div className="flex min-w-0 flex-col gap-8">
        <section className="flex flex-col gap-3">
          <Heading>{t("steward.npcs.name")}</Heading>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {LANGUAGES.map((language) => (
              <Name key={language} npc={npc} language={language} measured={measured} edit={edit} />
            ))}
          </div>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field id={pose} label={t("steward.npcs.pose")}>
              <Select
                value={npc.pose}
                onValueChange={(value) =>
                  edit((draft) => ({ ...draft, pose: POSES.find((each) => each === value) ?? draft.pose }))
                }
              >
                <SelectTrigger id={pose} className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {POSES.map((each) => (
                    <SelectItem key={each} value={each}>
                      {t("steward.npcs.pose-of", { pose: choice(each) })}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            <Pitch npc={npc} edit={edit} />
          </div>
        </section>
        <Place npc={npc} edit={edit} />
      </div>
      <div className="flex min-w-0 flex-col gap-8">
        <Skin npc={npc} published={published} edit={edit} />
        <Items npc={npc} edit={edit} />
      </div>
      {published === undefined ? null : (
        <div className="lg:col-span-2">
          <AskThenAct
            trigger={
              <Button type="button" variant="destructive">
                <TrashIcon aria-hidden />
                {t("steward.npcs.delete")}
              </Button>
            }
            title={t("steward.npcs.delete-ask", { name })}
            description={t("steward.npcs.delete-note")}
            action={t("steward.npcs.delete")}
            destructive
            act={onDelete}
          />
        </div>
      )}
    </div>
  )
}
