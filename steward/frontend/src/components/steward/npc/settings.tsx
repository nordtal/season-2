import { CopyIcon, TrashIcon, UploadSimpleIcon, XIcon } from "@/components/ui/icons"
import { useId, useRef, useState, type ReactNode } from "react"
import { toast } from "sonner"

import {
  errorMessage,
  type ConfigEntry,
  type Npc,
  type NpcPlace,
  type NpcSkinUploaded,
  type PlateColour,
  type PlateStyle,
  type Pose,
  type Slot,
} from "@/lib/api"
import { type Language, LONGEST_NAME } from "@/lib/npc"
import { NPC_PAGE } from "@/lib/npc-paths"
import { ARTWORK, SKIN_SIDE, readPicture, readSkin } from "@/lib/npc-skin"
import { artworkAddress, measureKey, useCopySkin, useUploadArtwork, useUploadSkin } from "@/lib/queries-npcs"
import { useGameData } from "@/lib/queries-settings"
import { gameKey, nameOfId } from "@/lib/references"
import { choice, t } from "@/lib/texts"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { GameIcon } from "@/components/steward/game-icon"
import { TakeInButton } from "@/components/steward/game-groups/take-in"
import { FixedPlace } from "@/components/steward/game-groups/places"
import { SettingsService } from "@/components/steward/reference-picker"
import { German, germanKey } from "@/components/steward/npc/german"
import { FigureCard, PLATE_ICONS, PLATE_RGB, PlateSketch, PlateSymbol } from "@/components/steward/npc/preview"
import { Heading, Room, useKept, type Measured } from "@/components/steward/npc/step-panel"
import { Hint } from "@/components/steward/status"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"

/**
 * Everything of an NPC but its dialogue: its name and plate, how it stands and sounds, where it stands, what it wears,
 * its skin and its portrait.
 */

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
  const measurement = useKept(
    text === "" ? undefined : measured?.get(measureKey({ text, kind: "NAME", answers: 0 })),
    text === "" ? "" : language,
  )
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

/** A facing as the NPC stores it: from 180 degrees left to 180 right, in tenths. */
function facing(yaw: number): number {
  const turned = ((((yaw + 180) % 360) + 360) % 360) - 180
  return Math.round(turned * 10) / 10
}

function hundredth(value: number): number {
  return Math.round(value * 100) / 100
}

/** The NPC's place as a spot of the settings: world, X, Y, Z and facing, so it draws as smp's places do. */
const PLACE_FIELDS: ConfigEntry[] = [
  placeField("world", t("steward.npcs.world"), "STRING", { refers: { to: "WORLD", optional: false } }),
  placeField("x", "X", "DECIMAL"),
  placeField("y", "Y", "DECIMAL"),
  placeField("z", "Z", "DECIMAL"),
  placeField("yaw", t("steward.npcs.facing"), "DECIMAL"),
]

function placeField(
  key: string,
  label: string,
  type: ConfigEntry["type"],
  more: Partial<ConfigEntry> = {},
): ConfigEntry {
  return {
    path: `place.${key}`,
    key,
    label,
    explanation: "",
    noExplanationNeeded: true,
    kind: "SCALAR",
    type,
    editable: true,
    secret: false,
    environmentOverridden: false,
    filled: true,
    ...more,
  }
}

function valuesOf(place: NpcPlace): Record<string, string> {
  return { world: place.world, x: String(place.x), y: String(place.y), z: String(place.z), yaw: String(place.yaw) }
}

/** A typed number as the NPC stores it: a facing in tenths from -180 to 180, a coordinate in hundredths. */
function stored(key: string, value: string): number | undefined {
  const number = Number(value.replace(",", "."))
  if (value.trim() === "" || !Number.isFinite(number)) return undefined
  return key === "yaw" ? facing(number) : hundredth(number)
}

/**
 * Whether what is typed stores as the place, so a value from elsewhere, such as a taken position, replaces it only
 * then and a number being typed keeps its digits.
 */
function agrees(typed: Record<string, string>, place: NpcPlace): boolean {
  return (
    typed.world === place.world &&
    (["x", "y", "z", "yaw"] as const).every((key) => stored(key, typed[key] ?? "") === place[key])
  )
}

/** The Position tile with Take my position, as smp's settings draw a spot. A typed number counts once it is one. */
function Place({ npc, edit }: { npc: Npc; edit: Edit }) {
  const [typed, setTyped] = useState(() => valuesOf(npc.place))
  const [from, setFrom] = useState(npc.place)
  if (from !== npc.place) {
    setFrom(npc.place)
    if (!agrees(typed, npc.place)) setTyped(valuesOf(npc.place))
  }
  const change = (key: string, value: string) => {
    setTyped((before) => ({ ...before, [key]: value }))
    if (key === "world") {
      edit((draft) => ({ ...draft, place: { ...draft.place, world: value } }))
      return
    }
    const number = stored(key, value)
    if (number === undefined) return
    edit((draft) => ({ ...draft, place: { ...draft.place, [key]: number } }))
  }
  return (
    <section className="flex flex-col gap-3">
      <Heading>{t("steward.npcs.place")}</Heading>
      <SettingsService value={NPC_PAGE}>
        <FixedPlace
          id="npc-place"
          name={t("steward.npcs.place")}
          position="SPOT"
          fields={PLACE_FIELDS}
          values={typed}
          disabled={false}
          invalid={npc.place.world.trim() === ""}
          onChange={change}
        />
      </SettingsService>
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
  const latest = useRef(0)
  const busy = upload.isPending || copy.isPending
  const wear = (skin: NpcSkinUploaded) => edit((draft) => ({ ...draft, skin: skin.id, skinModel: skin.model }))
  const chosen = async (picked: File) => {
    const turn = ++latest.current
    let read
    try {
      read = await readSkin(picked)
    } catch {
      if (turn !== latest.current) return
      toast.error(t("steward.npcs.skin-not-saved"), { description: t("steward.npcs.skin-unreadable") })
      return
    }
    // A file chosen after this one takes its place.
    if (turn !== latest.current) return
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
              const asked = name
              copy.mutateAsync(asked.trim()).then((skin) => {
                wear(skin)
                setName((typed) => (typed === asked ? "" : typed))
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

const STYLES: readonly PlateStyle[] = ["PILL", "DARK", "RIBBON", "BADGE"]
const PLATE_COLOURS: readonly PlateColour[] = ["BLUE", "RED", "AMBER", "GREEN", "TEAL", "VIOLET", "ROSE", "SLATE"]
const NO_ICON = "none"

/** The NPC's name plate: its style, its colour and the symbol before its name. */
/** How far each arrow key moves the chosen colour. */
const ARROW_STEPS: Partial<Record<string, number>> = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }

function Plate({ npc, edit }: { npc: Npc; edit: Edit }) {
  const style = useId()
  const icon = useId()
  const colour = useId()
  const swatches = useRef(new Map<PlateColour, HTMLButtonElement>())
  const plate = (change: Partial<Npc["plate"]>) => edit((draft) => ({ ...draft, plate: { ...draft.plate, ...change } }))
  return (
    <section className="flex flex-col gap-3">
      <Heading>{t("steward.npcs.plate")}</Heading>
      <div className="flex h-16 items-center justify-center overflow-hidden rounded-lg bg-muted px-4">
        <PlateSketch npc={npc} />
      </div>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <Field id={style} label={t("steward.npcs.plate-style")}>
          <Select
            value={npc.plate.style}
            onValueChange={(value) => plate({ style: STYLES.find((each) => each === value) ?? npc.plate.style })}
          >
            <SelectTrigger id={style} className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {STYLES.map((each) => (
                <SelectItem key={each} value={each}>
                  {t("steward.npcs.plate-style-of", { style: choice(each) })}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </Field>
        <Field id={icon} label={t("steward.npcs.plate-icon")}>
          <Select
            value={npc.plate.icon ?? NO_ICON}
            onValueChange={(value) => {
              const picked = PLATE_ICONS.find((each) => each === value)
              edit((draft) => {
                const { icon: _icon, ...rest } = draft.plate
                return { ...draft, plate: picked === undefined ? rest : { ...rest, icon: picked } }
              })
            }}
          >
            <SelectTrigger id={icon} className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={NO_ICON}>{t("steward.npcs.no-icon")}</SelectItem>
              {PLATE_ICONS.map((each) => (
                <SelectItem key={each} value={each}>
                  <PlateSymbol icon={each} size={14} />
                  {t("steward.npcs.plate-icon-of", { icon: choice(each) })}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </Field>
      </div>
      <div
        role="radiogroup"
        aria-labelledby={colour}
        className="flex flex-col gap-1.5"
        onKeyDown={(event) => {
          // One tab stop for the group; the arrow keys choose the next or previous colour, as a radio group does.
          const step = ARROW_STEPS[event.key]
          if (step === undefined) return
          event.preventDefault()
          const at = PLATE_COLOURS.indexOf(npc.plate.colour)
          const next = PLATE_COLOURS[(at + step + PLATE_COLOURS.length) % PLATE_COLOURS.length]
          if (next === undefined) return
          plate({ colour: next })
          swatches.current.get(next)?.focus()
        }}
      >
        <span id={colour} className="text-xs text-muted-foreground">
          {t("steward.npcs.plate-colour")}
        </span>
        <div className="flex flex-wrap gap-1">
          {PLATE_COLOURS.map((each) => {
            const rgb = PLATE_RGB[each]
            const chosen = npc.plate.colour === each
            const label = t("steward.npcs.plate-colour-of", { colour: choice(each) })
            return (
              <Hint key={each} tip={label}>
                <button
                  type="button"
                  role="radio"
                  aria-checked={chosen}
                  aria-label={label}
                  tabIndex={chosen ? 0 : -1}
                  ref={(button) => {
                    if (button === null) swatches.current.delete(each)
                    else swatches.current.set(each, button)
                  }}
                  className="tap-target flex size-8 items-center justify-center rounded-md focus-visible:outline-2 focus-visible:outline-ring"
                  onClick={() => plate({ colour: each })}
                >
                  <span
                    className={`size-6 rounded-full ${chosen ? "ring-2 ring-foreground ring-offset-2 ring-offset-background" : ""}`}
                    style={{ background: rgb }}
                  />
                </button>
              </Hint>
            )
          })}
        </div>
      </div>
      <p className="text-xs text-muted-foreground">{t("steward.npcs.plate-note")}</p>
    </section>
  )
}

/** The NPC's portrait in the dialogue: the skin's face, or a picture of its own. */
function Portrait({ npc, published, edit }: { npc: Npc; published: Npc | undefined; edit: Edit }) {
  const upload = useUploadArtwork()
  const file = useRef<HTMLInputElement>(null)
  const latest = useRef(0)
  const [small, setSmall] = useState(true)
  const chosen = async (picked: File) => {
    const turn = ++latest.current
    if (picked.size > ARTWORK.bytes) {
      toast.error(t("steward.npcs.portrait-not-saved"), { description: t("steward.npcs.portrait-too-big") })
      return
    }
    let read
    try {
      read = await readPicture(picked)
    } catch {
      if (turn !== latest.current) return
      toast.error(t("steward.npcs.portrait-not-saved"), { description: t("steward.npcs.skin-unreadable") })
      return
    }
    // A file chosen after this one takes its place.
    if (turn !== latest.current) return
    if (read.width !== read.height || read.width < ARTWORK.smallest || read.width > ARTWORK.largest) {
      toast.error(t("steward.npcs.portrait-not-saved"), {
        description: t("steward.npcs.portrait-size", {
          width: read.width,
          height: read.height,
          smallest: ARTWORK.smallest,
          largest: ARTWORK.largest,
        }),
      })
      return
    }
    // The promise, not the hook's callbacks, takes the answer: it arrives even after the admin left this tab.
    upload
      .mutateAsync(new Blob([picked], { type: "image/png" }))
      .then((artwork) => edit((draft) => ({ ...draft, artwork: artwork.id })), refused)
  }
  return (
    <section className="flex flex-col gap-3">
      <Heading>{t("steward.npcs.portrait")}</Heading>
      <div className="flex items-start gap-4">
        {npc.artwork === undefined ? null : (
          <img
            src={artworkAddress(npc.artwork)}
            alt=""
            width={80}
            height={80}
            className="size-20 shrink-0 rounded-lg bg-card"
            // Small pictures keep their pixels; larger ones are smoothed, closer to the pack's box average.
            style={{ imageRendering: small ? "pixelated" : "auto" }}
            onLoad={(event) => setSmall(event.currentTarget.naturalWidth <= 80)}
          />
        )}
        <div className="flex min-w-0 flex-1 flex-col gap-3">
          <p className="text-sm">
            {npc.artwork === undefined ? t("steward.npcs.skin-portrait") : t("steward.npcs.own-portrait")}
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
              disabled={upload.isPending}
              onClick={() => file.current?.click()}
            >
              <UploadSimpleIcon aria-hidden />
              {t("steward.npcs.upload-portrait")}
            </Button>
            {npc.artwork === undefined ? null : (
              <Button
                type="button"
                variant="ghost"
                className="max-sm:w-full"
                disabled={upload.isPending}
                onClick={() =>
                  edit((draft) => {
                    const { artwork: _artwork, ...rest } = draft
                    return rest
                  })
                }
              >
                {t("steward.npcs.use-skin-portrait")}
              </Button>
            )}
          </div>
        </div>
      </div>
      {npc.artwork !== undefined && npc.artwork !== published?.artwork ? (
        <p className="text-xs text-muted-foreground">{t("steward.npcs.portrait-pending")}</p>
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
          <Name npc={npc} language="en" measured={measured} edit={edit} />
          <German
            id={germanKey.name}
            missing={npc.name.de.trim() === ""}
            long={measured?.get(measureKey({ text: npc.name.de, kind: "NAME", answers: 0 }))?.fits === false}
          >
            <Name npc={npc} language="de" measured={measured} edit={edit} />
          </German>
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
        <Plate npc={npc} edit={edit} />
        <Place npc={npc} edit={edit} />
      </div>
      <div className="flex min-w-0 flex-col gap-8">
        <Skin npc={npc} published={published} edit={edit} />
        <Portrait npc={npc} published={published} edit={edit} />
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
