import { PlusIcon } from "@phosphor-icons/react"
import { useState } from "react"

import type { ConfigEntry } from "@/lib/api"
import { type Choice, choiceFor, nameOfId } from "@/lib/references"
import { ScalarControl } from "@/components/steward/config-controls"
import { EnchantmentBadges, itemTip, useItemDetails } from "@/components/steward/game-item"
import { Empty } from "@/components/steward/query-state"
import { Mark, useChoices } from "@/components/steward/reference-picker"
import { type SectionValues, blankSection } from "@/components/steward/repeatable-cards"
import { Hint } from "@/components/steward/status"
import { TERM_ICON } from "@/components/steward/term-icons"
import { count, field, holds, text } from "@/components/steward/game-groups/model"
import { AddPicker, NumberInput, RemoveButton } from "@/components/steward/game-groups/parts"
import { TakeInButton } from "@/components/steward/game-groups/take-in"
import { EntryDialog, Labelled, Tile, Tiles } from "@/components/steward/game-groups/tiles"
import { Button } from "@/components/ui/button"
import { mostEntries } from "@/lib/setting-rules"
import { t } from "@/lib/texts"

/** The loadout items a kit's tile shows; the rest are counted. */
const TILE_ITEMS = 4

/** Whether `entry` is a list of duel kits: a name, an icon and a loadout of items. */
export function isKitList(entry: ConfigEntry): boolean {
  if (!holds(entry, ["name", "icon", "loadout"]) || field(entry, "icon")?.refers === undefined) return false
  const loadout = field(entry, "loadout")!
  return holds(loadout, ["item", "amount"]) && field(loadout, "item")?.refers !== undefined
}

function sectionsIn(section: SectionValues, key: string): SectionValues[] {
  const value = section[key]
  return Array.isArray(value) ? value.filter((item): item is SectionValues => typeof item === "object") : []
}

/** Whether a section still holds what it was made with, so closing it untouched takes it out again. */
function untouched(section: SectionValues | undefined, made: SectionValues | undefined): boolean {
  if (section === undefined || made === undefined) return false
  return Object.keys({ ...section, ...made }).every((key) => JSON.stringify(section[key]) === JSON.stringify(made[key]))
}

/** An item's icon, with its amount at the corner when one is given; a warning when the catalogue lacks the item. */
function ItemMark({ choice, icons, registry, size, amount }: ItemMarkProps & { amount?: number }) {
  return (
    <span className="relative shrink-0">
      {choice ? (
        <Mark choice={choice} icons={icons} registry={registry} size={size} />
      ) : (
        <TERM_ICON.warning aria-hidden className="text-warning" style={{ width: size, height: size }} />
      )}
      {amount === undefined ? null : (
        <span className="absolute -right-2 -bottom-1 font-mono text-xs font-semibold tabular-nums">
          {amount.toLocaleString("en")}
        </span>
      )}
    </span>
  )
}

type ItemMarkProps = {
  choice: Choice | undefined
  icons: ReturnType<typeof useChoices>["icons"]
  registry: string | undefined
  size: number
}

/** The item catalogue, and how one item is drawn and named from it. */
function useItems(reference: NonNullable<ConfigEntry["refers"]>) {
  const { choices, icons } = useChoices(reference, undefined)
  return {
    mark: (id: string, size: number, amount?: number) => (
      <ItemMark
        choice={choiceFor(choices.choices, id)}
        icons={icons}
        registry={choices.registry}
        size={size}
        amount={amount}
      />
    ),
    nameOf: (id: string) => choiceFor(choices.choices, id)?.name ?? nameOfId(id),
  }
}

type Items = ReturnType<typeof useItems>

/** The duel kits: a tile for each, and Add, which opens the new kit's dialog. A list declared non-empty keeps its last kit. */
export function KitList({
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
  const [added, setAdded] = useState<{ index: number; values: SectionValues } | null>(null)
  const icon = field(entry, "icon")!
  const loadout = field(entry, "loadout")!
  const icons = useItems(icon.refers!)
  const items = useItems(field(loadout, "item")!.refers!)
  const most = mostEntries(entry.rules)
  const full = most !== undefined && value.length >= most
  const required = entry.rules?.some((rule) => rule.rule === "REQUIRED") ?? false
  const name = (index: number) =>
    text(value[index], "name").trim() || t("steward.game.kit-numbered", { index: index + 1 })
  const update = (index: number, key: string, next: string | SectionValues[]) =>
    onChange(value.map((section, at) => (at === index ? { ...section, [key]: next } : section)))
  const open = chosen !== null && chosen < value.length ? chosen : null

  return (
    <div className="@container flex flex-col gap-2">
      {value.length === 0 ? (
        <Empty title={t("steward.game.no-kits")} />
      ) : (
        <Tiles>
          {value.map((kit, index) => (
            <Tile
              key={index}
              mark={icons.mark(text(kit, "icon"), 40)}
              name={name(index)}
              details={<LoadoutGlance loadout={sectionsIn(kit, "loadout")} items={items} />}
              tip={
                sectionsIn(kit, "loadout").length === 0 ? undefined : (
                  <LoadoutTip loadout={sectionsIn(kit, "loadout")} items={items} />
                )
              }
              onOpen={() => choose(index)}
            />
          ))}
        </Tiles>
      )}
      <div>
        <Hint tip={full ? t("steward.game.kits-full", { most }) : undefined}>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={disabled || full}
            onClick={() => {
              const made = blankSection(entry.template ?? [])
              onChange([...value, made])
              setAdded({ index: value.length, values: made })
              choose(value.length)
            }}
          >
            <PlusIcon aria-hidden />
            {t("steward.game.kit")}
          </Button>
        </Hint>
      </div>
      {open !== null ? (
        <EntryDialog
          open
          mark={icons.mark(text(value[open], "icon"), 48)}
          name={name(open)}
          disabled={disabled}
          onRemove={required && value.length <= 1 ? undefined : () => onChange(value.filter((_, at) => at !== open))}
          onClose={() => {
            if (added?.index === open && untouched(value[open], added.values))
              onChange(value.filter((_, at) => at !== open))
            setAdded(null)
            choose(null)
          }}
        >
          <Kit
            id={`${entry.path}-${open}`}
            list={entry}
            kit={value[open]}
            name={name(open)}
            items={items}
            disabled={disabled}
            onSet={(key, next) => update(open, key, next)}
          />
        </EntryDialog>
      ) : null}
    </div>
  )
}

/** A short view of a kit's loadout on its tile: the first items, an amount above one, and how many more there are. */
function LoadoutGlance({ loadout, items }: { loadout: SectionValues[]; items: Items }) {
  if (loadout.length === 0) return <span className="text-xs text-muted-foreground">{t("steward.game.no-loadout")}</span>
  const shown = loadout.length <= TILE_ITEMS ? loadout : loadout.slice(0, TILE_ITEMS - 1)
  return (
    <span className="flex flex-wrap items-center justify-center gap-x-2.5 gap-y-1 pt-1">
      {shown.map((piece, index) => (
        <span key={index}>
          {items.mark(text(piece, "item"), 16, count(piece, "amount") > 1 ? count(piece, "amount") : undefined)}
        </span>
      ))}
      {loadout.length > shown.length ? (
        <span className="text-xs text-muted-foreground">
          {t("steward.game.more-items", { count: loadout.length - shown.length })}
        </span>
      ) : null}
    </span>
  )
}

/** A kit's tooltip: every item of its loadout, one per line, by the name it carries in the game. */
function LoadoutTip({ loadout, items }: { loadout: SectionValues[]; items: Items }) {
  return (
    <ul className="flex flex-col gap-0.5 text-left text-xs">
      {loadout.map((piece, index) => (
        <LoadoutLine key={index} piece={piece} items={items} />
      ))}
    </ul>
  )
}

function LoadoutLine({ piece, items }: { piece: SectionValues; items: Items }) {
  const details = useItemDetails(text(piece, "data"))
  const name = details.name ?? items.nameOf(text(piece, "item"))
  return <li>{t("steward.game.loadout-line", { amount: count(piece, "amount"), name })}</li>
}

/** A kit's name, icon and loadout. An item of the loadout is picked from the catalogue or taken in whole from the game. */
function Kit({
  id,
  list,
  kit,
  name,
  items,
  disabled,
  onSet,
}: {
  id: string
  list: ConfigEntry
  kit: SectionValues
  name: string
  items: Items
  disabled: boolean
  onSet: (key: string, value: string | SectionValues[]) => void
}) {
  const loadout = field(list, "loadout")!
  const pieces = sectionsIn(kit, "loadout")
  const add = (piece: SectionValues) =>
    onSet("loadout", [...pieces, { ...blankSection(loadout.template ?? []), ...piece }])
  const setPiece = (index: number, key: string, value: string) =>
    onSet(
      "loadout",
      pieces.map((piece, at) => (at === index ? { ...piece, [key]: value } : piece)),
    )
  const scalar = (key: string) => {
    const entry = field(list, key)
    return entry === undefined ? null : (
      <Labelled id={`${id}-${key}`} label={entry.label}>
        <ScalarControl
          id={`${id}-${key}`}
          entry={entry}
          value={text(kit, key)}
          disabled={disabled}
          onChange={(value) => onSet(key, value)}
        />
      </Labelled>
    )
  }
  return (
    <div className="flex flex-col gap-4">
      {scalar("name")}
      {scalar("icon")}
      <div className="flex flex-col gap-2">
        <div className="flex items-center justify-between gap-2">
          <span className="text-sm font-medium">{loadout.label}</span>
          <AddPicker
            id={`${id}-add`}
            label={t("steward.game.item")}
            reference={field(loadout, "item")!.refers!}
            disabled={disabled}
            action={
              field(loadout, "data") === undefined ? undefined : (
                <TakeInButton purpose="DUEL_KIT" disabled={disabled} onTaken={add} />
              )
            }
            onPick={(item) => add({ item })}
          />
        </div>
        {pieces.length === 0 ? (
          <p className="text-xs text-muted-foreground">{t("steward.game.no-loadout")}</p>
        ) : (
          <ul className="flex flex-col divide-y rounded-lg border">
            {pieces.map((piece, index) => (
              <Piece
                key={index}
                id={`${id}-loadout-${index}`}
                list={loadout}
                piece={piece}
                kit={name}
                items={items}
                disabled={disabled}
                onSet={(key, value) => setPiece(index, key, value)}
                onRemove={() =>
                  onSet(
                    "loadout",
                    pieces.filter((_, at) => at !== index),
                  )
                }
              />
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

/** One item of a loadout as one element: its icon, the name and enchantments it carries in the game, and its amount. */
function Piece({
  id,
  list,
  piece,
  kit,
  items,
  disabled,
  onSet,
  onRemove,
}: {
  id: string
  list: ConfigEntry
  piece: SectionValues
  kit: string
  items: Items
  disabled: boolean
  onSet: (key: string, value: string) => void
  onRemove: () => void
}) {
  const details = useItemDetails(text(piece, "data"))
  const name = details.name ?? items.nameOf(text(piece, "item"))
  const amount = field(list, "amount")
  return (
    <li className="flex items-center gap-3 px-3 py-2">
      <Hint tip={itemTip(details)}>
        <span className="flex min-w-0 flex-1 items-center gap-3">
          {items.mark(text(piece, "item"), 32)}
          <span className="flex min-w-0 flex-col items-start gap-1">
            <span className="truncate text-sm">{name}</span>
            <EnchantmentBadges enchantments={details.enchantments} most={2} className="justify-start" />
          </span>
        </span>
      </Hint>
      <NumberInput
        id={`${id}-amount`}
        label={`${kit} ${name} ${amount?.label ?? "amount"}`}
        entry={amount}
        value={text(piece, "amount")}
        disabled={disabled}
        className="w-20"
        onChange={(value) => onSet("amount", value)}
      />
      <RemoveButton name={name} disabled={disabled} onRemove={onRemove} />
    </li>
  )
}
