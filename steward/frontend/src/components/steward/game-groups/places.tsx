import { PlusIcon } from "@/components/ui/icons"
import { type ReactNode, useId, useState } from "react"

import type { ConfigEntry } from "@/lib/api"
import { type Choice, choiceFor, worldIcon } from "@/lib/references"
import { FieldProblem, ScalarControl } from "@/components/steward/config-controls"
import { useBreach, useBreaches } from "@/components/steward/breach-context"
import { Empty } from "@/components/steward/query-state"
import { Hint } from "@/components/steward/status"
import { Mark, useChoices } from "@/components/steward/reference-picker"
import { type SectionValues, blankSection, notSaved } from "@/components/steward/repeatable-cards"
import { AREA, LABEL, type PlaceShape, shapeOf, text } from "@/components/steward/game-groups/model"
import { NumberInput } from "@/components/steward/game-groups/parts"
import { TakePosition, takenValues } from "@/components/steward/game-groups/take-position"
import { EntryDialog, Labelled } from "@/components/steward/game-groups/tiles"
import { Button } from "@/components/ui/button"
import { mostEntries } from "@/lib/setting-rules"
import { t } from "@/lib/texts"
import { cn } from "@/lib/utils"

/** The words of a list of places: its Add button, a place without a label, and the empty list. */
const WORDS = {
  area: { add: "steward.game.area", numbered: "steward.game.area-numbered", none: "steward.game.no-areas" },
  block: { add: "steward.game.block", numbered: "steward.game.block-numbered", none: "steward.game.no-blocks" },
  point: { add: "steward.game.point", numbered: "steward.game.point-numbered", none: "steward.game.no-points" },
  spot: { add: "steward.game.spot", numbered: "steward.game.spot-numbered", none: "steward.game.no-spots" },
} as const

/** The fields of one place by key, its values by key, and how its values change, several at once for a take. */
type Place = {
  shape: PlaceShape
  fields: ConfigEntry[]
  values: SectionValues
  /** Whether the schema marks it as a position, which the admin's player can fill from the game. */
  takes?: boolean
  /** Whether the shown value of `key` is a default that the stored list lacks. */
  unsaved?: (key: string) => boolean
  onChange: (changes: Record<string, string>) => void
}

/** The keys of a place's coordinates, which the dialog draws in rows of their own. */
function coordinatesOf(shape: PlaceShape): readonly string[] {
  return shape === "area" ? AREA.keys : shape === "centre" ? ["x", "z"] : ["x", "y", "z"]
}

function fieldOf(fields: ConfigEntry[], key: string): ConfigEntry | undefined {
  return fields.find((field) => field.key === key)
}

/** The world's icon: Overworld, Nether or End, also while the server has not reported its worlds. */
function WorldMark({ world, reference, size }: { world: ConfigEntry | undefined; reference: string; size: number }) {
  const { choices, icons } = useChoices(world?.refers ?? { to: "WORLD", optional: false }, undefined)
  const choice: Choice = choiceFor(choices.choices, reference) ?? {
    id: reference,
    name: reference,
    icon: worldIcon(reference),
  }
  return <Mark choice={choice} icons={icons} registry={undefined} size={size} />
}

/** The world's icon of a place in a world; a labelled point in its game's one world has none. */
function PlaceMark({ place, size }: { place: Pick<Place, "fields" | "values">; size: number }) {
  const world = fieldOf(place.fields, "world")
  if (world === undefined) return null
  return <WorldMark world={world} reference={text(place.values, "world")} size={size} />
}

/** The label of a place that has one, such as a loot point's "north"; blank when it has none. */
export function labelOf(place: Pick<Place, "fields" | "values">): string {
  return fieldOf(place.fields, LABEL) === undefined ? "" : text(place.values, LABEL).trim()
}

/** One coordinate on a tile: its axis muted, its value in tabular figures. */
function Axis({ axis, children }: { axis: string; children: ReactNode }) {
  return (
    <span className="inline-flex items-baseline gap-1">
      <span className="text-xs text-muted-foreground">{axis}</span>
      <span className="font-mono tnum">{children}</span>
    </span>
  )
}

/**
 * What a tile shows under the world: an area's two corners and its height, a block's or a spot's three coordinates,
 * or a centre's X and Z.
 */
function Coordinates({ shape, values }: { shape: PlaceShape; values: SectionValues }) {
  if (shape !== "area") {
    return (
      <span className="flex flex-wrap gap-x-3">
        {coordinatesOf(shape).map((key) => (
          <Axis key={key} axis={key.toUpperCase()}>
            {text(values, key)}
          </Axis>
        ))}
      </span>
    )
  }
  return (
    <span className="flex flex-col gap-0.5">
      {AREA.corners.map(([x, z]) => (
        <span key={x} className="flex flex-wrap gap-x-3">
          <Axis axis="X">{text(values, x)}</Axis>
          <Axis axis="Z">{text(values, z)}</Axis>
        </span>
      ))}
      <Axis axis="Y">
        {t("steward.game.span", { from: text(values, AREA.height[0]), to: text(values, AREA.height[1]) })}
      </Axis>
    </span>
  )
}

/**
 * A read-only tile of one place: its label, or the world with its icon, then the coordinates. A click opens its
 * dialog. `invalid` marks a tile whose values break a rule, so the admin sees which one to open.
 */
export function PlaceTile({
  place,
  label,
  invalid = false,
  onOpen,
}: {
  place: Pick<Place, "shape" | "fields" | "values">
  label: string
  invalid?: boolean
  onOpen: () => void
}) {
  const world = text(place.values, "world")
  const labelled = fieldOf(place.fields, LABEL) !== undefined
  const inWorld = fieldOf(place.fields, "world") !== undefined
  const described = useId()
  return (
    <button
      type="button"
      aria-haspopup="dialog"
      aria-label={label}
      aria-describedby={described}
      aria-invalid={invalid || undefined}
      className={cn(
        "flex h-full w-full min-w-0 flex-col gap-2 rounded-lg border px-3 py-2.5 text-left text-sm transition-colors hover:bg-muted/50",
        invalid && "border-destructive",
      )}
      onClick={onOpen}
    >
      <span id={described} className="flex min-w-0 flex-col gap-2">
        <span className="flex min-w-0 items-center gap-2">
          <PlaceMark place={place} size={24} />
          <span className="truncate font-medium">
            {labelled ? labelOf(place) || label : inWorld ? world || t("steward.game.no-world") : label}
          </span>
        </span>
        <Coordinates shape={place.shape} values={place.values} />
      </span>
    </button>
  )
}

/**
 * A number of the place. A screen reader hears the schema's label, such as "Corner 1 X"; under a row's name the
 * eye sees `shown` alone, such as "X".
 */
function Coordinate({
  id,
  place,
  field,
  shown,
  disabled,
}: {
  id: string
  place: Place
  field: string
  shown?: string
  disabled: boolean
}) {
  const entry = fieldOf(place.fields, field)
  if (entry === undefined) return null
  return (
    <Labelled id={`${id}-${field}`} label={shown ?? entry.label}>
      <NumberInput
        id={`${id}-${field}`}
        label={entry.label}
        entry={entry}
        value={text(place.values, field)}
        unsaved={place.unsaved?.(field)}
        disabled={disabled}
        className="w-full"
        onChange={(value) => place.onChange({ [field]: value })}
      />
    </Labelled>
  )
}

/** A row of the dialog: its name, then its fields side by side. */
function Row({ name, children }: { name?: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      {name ? <span className="text-sm font-medium">{name}</span> : null}
      <div className="grid grid-cols-2 gap-2 has-[>:nth-child(3)]:grid-cols-3">{children}</div>
    </div>
  )
}

/** A field of the place beside its coordinates, such as a facing or a board's kind. */
function Other({ id, place, field, disabled }: { id: string; place: Place; field: ConfigEntry; disabled: boolean }) {
  if (field.type === "INTEGER" || field.type === "DECIMAL") {
    return <Coordinate id={id} place={place} field={field.key} disabled={disabled} />
  }
  return (
    <Labelled id={`${id}-${field.key}`} label={field.label}>
      <ScalarControl
        id={`${id}-${field.key}`}
        entry={field}
        value={text(place.values, field.key)}
        disabled={disabled}
        onChange={(value) => place.onChange({ [field.key]: value })}
      />
    </Labelled>
  )
}

/**
 * The fields of the dialog: the label, the world, the buttons that take the place from the game, then corner 1,
 * corner 2 and the height, X, Y and Z, or X and Z; any other field after them. The label's id is the form's,
 * `<id>.label`, so a label that breaks a rule shows it there.
 */
function PlaceFields({ id, place, disabled }: { id: string; place: Place; disabled: boolean }) {
  const label = fieldOf(place.fields, LABEL)
  const world = fieldOf(place.fields, "world")
  const drawn = new Set([LABEL, "world", ...coordinatesOf(place.shape)])
  const rest = place.fields.filter((field) => !drawn.has(field.key) && field.kind === "SCALAR")
  return (
    <div className="flex flex-col gap-4">
      {label ? (
        <Labelled id={`${id}.${LABEL}`} label={label.label}>
          <ScalarControl
            id={`${id}.${LABEL}`}
            entry={label}
            value={text(place.values, LABEL)}
            disabled={disabled}
            onChange={(value) => place.onChange({ [LABEL]: value })}
          />
        </Labelled>
      ) : null}
      {world ? (
        <Labelled id={`${id}-world`} label={world.label}>
          <ScalarControl
            id={`${id}-world`}
            entry={world}
            value={text(place.values, "world")}
            disabled={disabled}
            onChange={(value) => place.onChange({ world: value })}
          />
        </Labelled>
      ) : null}
      {place.takes ? (
        <TakePosition
          shape={place.shape}
          disabled={disabled}
          onTaken={(taken, corner) => place.onChange(takenValues(place.shape, place.fields, taken, corner))}
        />
      ) : null}
      {place.shape === "area" ? (
        <>
          {AREA.corners.map((corner, index) => (
            <Row key={corner[0]} name={t("steward.game.corner", { index: index + 1 })}>
              {corner.map((key) => (
                <Coordinate
                  key={key}
                  id={id}
                  place={place}
                  field={key}
                  shown={key.slice(-1).toUpperCase()}
                  disabled={disabled}
                />
              ))}
            </Row>
          ))}
          <Row name={t("steward.game.height")}>
            {AREA.height.map((key) => (
              <Coordinate key={key} id={id} place={place} field={key} disabled={disabled} />
            ))}
          </Row>
        </>
      ) : (
        <Row>
          {coordinatesOf(place.shape).map((key) => (
            <Coordinate key={key} id={id} place={place} field={key} disabled={disabled} />
          ))}
        </Row>
      )}
      {rest.length > 0 ? (
        <Row>
          {rest.map((field) => (
            <Other key={field.key} id={id} place={place} field={field} disabled={disabled} />
          ))}
        </Row>
      ) : null}
    </div>
  )
}

/** The dialog of one place, a sheet on a phone. Without `onRemove` the place is fixed and has no Remove. */
function PlaceDialog({
  id,
  name,
  description,
  place,
  disabled,
  onRemove,
  onClose,
}: {
  id: string
  name: string
  description?: string
  place: Place
  disabled: boolean
  onRemove?: () => void
  onClose: () => void
}) {
  return (
    <EntryDialog
      open
      mark={<PlaceMark place={place} size={40} />}
      name={name}
      description={description}
      disabled={disabled}
      onRemove={onRemove}
      onClose={onClose}
    >
      <PlaceFields id={id} place={place} disabled={disabled} />
    </EntryDialog>
  )
}

/**
 * One fixed place, such as the arena: its tile, and its dialog, which edits it and cannot remove it. `position` is
 * the shape the schema names, which also lets the admin take it from the game.
 */
export function FixedPlace({
  id,
  name,
  description,
  position,
  fields,
  values,
  disabled,
  invalid = false,
  onChange,
}: {
  id: string
  name: string
  description?: string
  position?: ConfigEntry["position"]
  fields: ConfigEntry[]
  values: SectionValues
  disabled: boolean
  /** Marks the tile as one the admin still has to fill. */
  invalid?: boolean
  onChange: (key: string, value: string) => void
}) {
  const [open, setOpen] = useState(false)
  const shape = shapeOf(
    position,
    fields.map((field) => field.key),
  )
  if (shape === undefined) return null
  const place: Place = {
    shape,
    fields,
    values,
    takes: position !== undefined,
    onChange: (changes) => Object.entries(changes).forEach(([key, value]) => onChange(key, value)),
  }
  return (
    <>
      <PlaceTile place={place} label={name} invalid={invalid} onOpen={() => setOpen(true)} />
      {open ? (
        <PlaceDialog
          id={id}
          name={name}
          description={description}
          place={place}
          disabled={disabled}
          onClose={() => setOpen(false)}
        />
      ) : null}
    </>
  )
}

/** A list of places, such as the protected areas: a tile for each, and Add, which opens the new one's dialog. */
export function PlaceList({
  entry,
  value,
  disabled,
  onChange,
}: {
  entry: ConfigEntry
  value: SectionValues[]
  disabled: boolean
  onChange: (value: SectionValues[]) => void
}) {
  const [chosen, choose] = useState<number | null>(null)
  /** The area Add just made, as it was made: closed untouched, it goes again, so a stray click saves nothing. */
  const [added, setAdded] = useState<{ index: number; values: SectionValues } | null>(null)
  const breach = useBreach(entry.path)
  const breachAt = useBreaches()
  const fields = entry.template ?? []
  const shape = shapeOf(
    entry.position,
    fields.map((field) => field.key),
  )
  if (shape === undefined) return null
  /** A labelled place is named by its label, as a card is by its name; without one, by its place in the list. */
  /** A labelled point in its game's one world is a point, not a block. */
  const point = fields.some((field) => field.key === LABEL) && !fields.some((field) => field.key === "world")
  const words = WORDS[shape === "area" ? "area" : shape === "spot" ? "spot" : point ? "point" : "block"]
  const name = (index: number) => labelOf({ fields, values: value[index] }) || t(words.numbered, { index: index + 1 })
  const invalid = (index: number) => fields.some((field) => breachAt(`${entry.path}.${index}.${field.key}`) !== null)
  const placeAt = (index: number): Place => ({
    shape,
    fields,
    values: value[index],
    takes: entry.position !== undefined,
    unsaved: (key) => notSaved(entry, index, key, text(value[index], key)),
    onChange: (changes) => onChange(value.map((section, at) => (at === index ? { ...section, ...changes } : section))),
  })
  const open = chosen !== null && chosen < value.length ? chosen : null
  const most = mostEntries(entry.rules)
  const full = most !== undefined && value.length >= most

  return (
    <div className="flex flex-col gap-2">
      {value.length === 0 ? (
        <Empty title={t(words.none)} />
      ) : (
        <ul className="grid grid-cols-[repeat(auto-fill,minmax(10rem,1fr))] gap-2">
          {value.map((section, index) => (
            <li key={index}>
              <PlaceTile
                place={{ shape, fields, values: section }}
                label={name(index)}
                invalid={invalid(index)}
                onOpen={() => choose(index)}
              />
            </li>
          ))}
        </ul>
      )}
      <FieldProblem id={`${entry.path}-breach`} problem={breach} />
      <div>
        <Hint tip={full ? t("steward.settings.list-full", { most }) : undefined}>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={disabled || full}
            onClick={() => {
              // A new area starts in the world of the one before it, which is where the next one usually is.
              const before = value.at(-1)
              const blank = blankSection(fields)
              const made = before === undefined ? blank : { ...blank, world: text(before, "world") }
              onChange([...value, made])
              setAdded({ index: value.length, values: made })
              choose(value.length)
            }}
          >
            <PlusIcon aria-hidden />
            {t(words.add)}
          </Button>
        </Hint>
      </div>
      {open !== null ? (
        <PlaceDialog
          id={`${entry.path}.${open}`}
          name={name(open)}
          place={placeAt(open)}
          disabled={disabled}
          onRemove={() => onChange(value.filter((_, at) => at !== open))}
          onClose={() => {
            const section = added !== null && added.index === open ? value[open] : undefined
            const untouched =
              section !== undefined &&
              Object.keys({ ...section, ...added?.values }).every((key) => section[key] === added?.values[key])
            if (untouched) onChange(value.filter((_, at) => at !== open))
            setAdded(null)
            choose(null)
          }}
        />
      ) : null}
    </div>
  )
}
