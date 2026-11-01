import { PlusIcon } from "@phosphor-icons/react"
import { type ReactNode, useState } from "react"

import type { ConfigEntry } from "@/lib/api"
import { type Choice, choiceFor, worldIcon } from "@/lib/references"
import { ScalarControl } from "@/components/steward/config-controls"
import { Empty } from "@/components/steward/query-state"
import { Mark, useChoices } from "@/components/steward/reference-picker"
import { type SectionValues, blankSection, notSaved } from "@/components/steward/repeatable-cards"
import { AREA, type PlaceShape, placeShape, text } from "@/components/steward/game-groups/model"
import { NumberInput } from "@/components/steward/game-groups/parts"
import { EntryDialog, Labelled } from "@/components/steward/game-groups/tiles"
import { Button } from "@/components/ui/button"
import { t } from "@/lib/texts"

/** The fields of one place by key, its values by key, and how a value changes. */
type Place = {
  shape: PlaceShape
  fields: ConfigEntry[]
  values: SectionValues
  /** Whether the shown value of `key` is a default that the stored list lacks. */
  unsaved?: (key: string) => boolean
  onChange: (key: string, value: string) => void
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

/** One coordinate on a tile: its axis muted, its value in tabular figures. */
function Axis({ axis, children }: { axis: string; children: ReactNode }) {
  return (
    <span className="inline-flex items-baseline gap-1">
      <span className="text-xs text-muted-foreground">{axis}</span>
      <span className="font-mono tnum">{children}</span>
    </span>
  )
}

/** What a tile shows under the world: an area's two corners and its height, or a block's three coordinates. */
function Coordinates({ shape, values }: { shape: PlaceShape; values: SectionValues }) {
  if (shape === "block") {
    return (
      <span className="flex flex-wrap gap-x-3">
        {(["x", "y", "z"] as const).map((key) => (
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

/** A read-only tile of one place: the world with its icon, then the coordinates. A click opens its dialog. */
export function PlaceTile({
  place,
  label,
  onOpen,
}: {
  place: Pick<Place, "shape" | "fields" | "values">
  label: string
  onOpen: () => void
}) {
  const world = text(place.values, "world")
  return (
    <button
      type="button"
      aria-haspopup="dialog"
      aria-label={label}
      className="flex h-full w-full min-w-0 flex-col gap-2 rounded-lg border px-3 py-2.5 text-left text-sm transition-colors hover:bg-muted/50"
      onClick={onOpen}
    >
      <span className="flex min-w-0 items-center gap-2">
        <WorldMark world={fieldOf(place.fields, "world")} reference={world} size={24} />
        <span className="truncate font-medium">{world || t("steward.game.no-world")}</span>
      </span>
      <Coordinates shape={place.shape} values={place.values} />
    </button>
  )
}

/** A number of the place, labelled by the schema so a screen reader hears "Corner 1 X". */
function Coordinate({ id, place, field, disabled }: { id: string; place: Place; field: string; disabled: boolean }) {
  const entry = fieldOf(place.fields, field)
  if (entry === undefined) return null
  return (
    <Labelled id={`${id}-${field}`} label={entry.label}>
      <NumberInput
        id={`${id}-${field}`}
        label={entry.label}
        entry={entry}
        value={text(place.values, field)}
        unsaved={place.unsaved?.(field)}
        disabled={disabled}
        className="w-full"
        onChange={(value) => place.onChange(field, value)}
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

/** The fields of the dialog: the world, then corner 1, corner 2 and the height, or X, Y and Z; any other field after them. */
function PlaceFields({ id, place, disabled }: { id: string; place: Place; disabled: boolean }) {
  const world = fieldOf(place.fields, "world")
  const drawn = new Set(["world", ...(place.shape === "area" ? AREA.keys : ["x", "y", "z"])])
  const rest = place.fields.filter((field) => !drawn.has(field.key) && field.kind === "SCALAR")
  return (
    <div className="flex flex-col gap-4">
      {world ? (
        <Labelled id={`${id}-world`} label={world.label}>
          <ScalarControl
            id={`${id}-world`}
            entry={world}
            value={text(place.values, "world")}
            disabled={disabled}
            onChange={(value) => place.onChange("world", value)}
          />
        </Labelled>
      ) : null}
      {place.shape === "area" ? (
        <>
          {AREA.corners.map((corner, index) => (
            <Row key={corner[0]} name={t("steward.game.corner", { index: index + 1 })}>
              {corner.map((key) => (
                <Coordinate key={key} id={id} place={place} field={key} disabled={disabled} />
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
          {(["x", "y", "z"] as const).map((key) => (
            <Coordinate key={key} id={id} place={place} field={key} disabled={disabled} />
          ))}
        </Row>
      )}
      {rest.length > 0 ? (
        <Row>
          {rest.map((field) => (
            <Coordinate key={field.key} id={id} place={place} field={field.key} disabled={disabled} />
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
      mark={<WorldMark world={fieldOf(place.fields, "world")} reference={text(place.values, "world")} size={40} />}
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

/** One fixed place, such as the arena: its tile, and its dialog, which edits it and cannot remove it. */
export function FixedPlace({
  id,
  name,
  description,
  fields,
  values,
  disabled,
  onChange,
}: {
  id: string
  name: string
  description?: string
  fields: ConfigEntry[]
  values: SectionValues
  disabled: boolean
  onChange: (key: string, value: string) => void
}) {
  const [open, setOpen] = useState(false)
  const shape = placeShape(fields.map((field) => field.key))
  if (shape === undefined) return null
  const place: Place = { shape, fields, values, onChange }
  return (
    <>
      <PlaceTile place={place} label={name} onOpen={() => setOpen(true)} />
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
  const fields = entry.template ?? []
  const shape = placeShape(fields.map((field) => field.key))
  if (shape === undefined) return null
  const name = (index: number) =>
    t(shape === "area" ? "steward.game.area-numbered" : "steward.game.block-numbered", { index: index + 1 })
  const placeAt = (index: number): Place => ({
    shape,
    fields,
    values: value[index],
    unsaved: (key) => notSaved(entry, index, key, text(value[index], key)),
    onChange: (key, next) =>
      onChange(value.map((section, at) => (at === index ? { ...section, [key]: next } : section))),
  })
  const open = chosen !== null && chosen < value.length ? chosen : null

  return (
    <div className="flex flex-col gap-2">
      {value.length === 0 ? (
        <Empty title={t(shape === "area" ? "steward.game.no-areas" : "steward.game.no-blocks")} />
      ) : (
        <ul className="grid grid-cols-[repeat(auto-fill,minmax(10rem,1fr))] gap-2">
          {value.map((section, index) => (
            <li key={index}>
              <PlaceTile place={{ shape, fields, values: section }} label={name(index)} onOpen={() => choose(index)} />
            </li>
          ))}
        </ul>
      )}
      <div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={disabled}
          onClick={() => {
            onChange([...value, blankSection(fields)])
            choose(value.length)
          }}
        >
          <PlusIcon aria-hidden />
          {t(shape === "area" ? "steward.game.area" : "steward.game.block")}
        </Button>
      </div>
      {open !== null ? (
        <PlaceDialog
          id={`${entry.path}-${open}`}
          name={name(open)}
          place={placeAt(open)}
          disabled={disabled}
          onRemove={() => onChange(value.filter((_, at) => at !== open))}
          onClose={() => choose(null)}
        />
      ) : null}
    </div>
  )
}
