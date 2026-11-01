import { type ReactNode, useState } from "react"

import type { ConfigDocument, ConfigEntry } from "@/lib/api"
import { choiceFor, nameOfId } from "@/lib/references"
import { draftSections } from "@/components/steward/configuration"
import type { CustomEditorProps } from "@/components/steward/config-editors"
import { GroupEditor } from "@/components/steward/group-draft"
import { Pill } from "@/components/steward/milestones/parts"
import { EnchantmentBadges, ItemLines, itemTip, useItemDetails } from "@/components/steward/game-item"
import { Empty } from "@/components/steward/query-state"
import { Mark, useChoices } from "@/components/steward/reference-picker"
import { type SectionValues, blankSection } from "@/components/steward/repeatable-cards"
import { TERM_ICON } from "@/components/steward/term-icons"
import { BLOCK_KEYS, chance, count, field, listEntry, sum, text } from "@/components/steward/game-groups/model"
import { AddPicker, Heading, NumberInput } from "@/components/steward/game-groups/parts"
import { PlaceList } from "@/components/steward/game-groups/places"
import { TakeInButton } from "@/components/steward/game-groups/take-in"
import { EntryDialog, Labelled, Tile, Tiles } from "@/components/steward/game-groups/tiles"
import { t } from "@/lib/texts"

const KEYS = ["item", "amount", "weight"]

export function readsWheel(document: ConfigDocument): boolean {
  const list = listEntry(document.entries, KEYS)
  return list !== undefined && field(list, "item")?.refers !== undefined
}

export function WheelEditor({ file, document }: CustomEditorProps) {
  const prizes = listEntry(document.entries, KEYS)
  const blocks = listEntry(document.entries, BLOCK_KEYS)
  if (prizes === undefined) return null
  const disabled = !document.writable
  return (
    <GroupEditor file={file} document={document}>
      {(draft, set) => (
        <>
          <Prizes
            list={prizes}
            prizes={draftSections(draft, prizes)}
            disabled={disabled}
            onChange={(next) => set(prizes.path, next)}
          />
          {blocks ? (
            <Blocks
              list={blocks}
              blocks={draftSections(draft, blocks)}
              disabled={disabled}
              onChange={(next) => set(blocks.path, next)}
            />
          ) : null}
        </>
      )}
    </GroupEditor>
  )
}

/** A tile per prize shows its item, amount and chance; a tile opens the prize's dialog, which edits amount and weight. */
function Prizes({
  list,
  prizes,
  disabled,
  onChange,
}: {
  list: ConfigEntry
  prizes: SectionValues[]
  disabled: boolean
  onChange: (next: SectionValues[]) => void
}) {
  const [chosen, choose] = useState<number | null>(null)
  const item = field(list, "item")!
  const { choices, icons } = useChoices(item.refers!, undefined)
  const total = sum(prizes, "weight")
  const nameOf = (id: string) => choiceFor(choices.choices, id)?.name ?? nameOfId(id)
  const update = (index: number, key: string, value: string) =>
    onChange(prizes.map((prize, at) => (at === index ? { ...prize, [key]: value } : prize)))
  const mark = (prize: SectionValues, size: number) => {
    const choice = choiceFor(choices.choices, text(prize, "item"))
    return (
      <span className="relative shrink-0">
        {choice ? (
          <Mark choice={choice} icons={icons} registry={choices.registry} size={size} />
        ) : (
          <TERM_ICON.warning aria-hidden className="text-warning" style={{ width: size, height: size }} />
        )}
        <span className="absolute -right-2 -bottom-1 font-mono text-xs font-semibold tabular-nums">
          {count(prize, "amount").toLocaleString("en")}
        </span>
      </span>
    )
  }
  const add = (prize: SectionValues) => {
    onChange([...prizes, { ...blankSection(list.template ?? []), ...prize }])
    choose(prizes.length)
  }
  const open = chosen !== null && chosen < prizes.length ? chosen : null
  const prize = open === null ? undefined : prizes[open]

  return (
    <section className="@container flex flex-col gap-3">
      <Heading
        actions={
          <AddPicker
            id={`${list.path}-add`}
            label={t("steward.game.prize")}
            reference={item.refers!}
            disabled={disabled}
            action={
              field(list, "data") === undefined ? undefined : (
                <TakeInButton purpose="WHEEL_PRIZE" disabled={disabled} onTaken={add} />
              )
            }
            onPick={(id) => add({ item: id })}
          />
        }
      >
        {list.label}
      </Heading>
      <div className="flex flex-wrap gap-1.5">
        <Pill tip={t("steward.game.prizes", { count: prizes.length })}>
          <TERM_ICON.item
            aria-label={t("steward.game.prizes", { count: prizes.length })}
            className="size-3.5 shrink-0"
          />
          {prizes.length.toLocaleString("en")}
        </Pill>
        <Pill>{t("steward.game.total-weight", { total })}</Pill>
      </div>
      {prizes.length === 0 ? (
        <Empty title={t("steward.game.no-prizes")} />
      ) : (
        <Tiles>
          {prizes.map((entry, index) => (
            <PrizeTile
              key={index}
              data={text(entry, "data")}
              mark={mark(entry, 40)}
              name={nameOf(text(entry, "item"))}
              details={chance(count(entry, "weight"), total)}
              onOpen={() => choose(index)}
            />
          ))}
        </Tiles>
      )}
      {open !== null && prize !== undefined ? (
        <PrizeDialog
          data={text(prize, "data")}
          mark={mark(prize, 48)}
          name={nameOf(text(prize, "item"))}
          description={choiceFor(choices.choices, text(prize, "item"))?.description}
          disabled={disabled}
          onRemove={() => onChange(prizes.filter((_, at) => at !== open))}
          onClose={() => choose(null)}
        >
          {(shown) => (
            <Prize
              list={list}
              prize={prize}
              index={open}
              name={shown}
              total={total}
              disabled={disabled}
              onSet={(key, value) => update(open, key, value)}
            />
          )}
        </PrizeDialog>
      ) : null}
    </section>
  )
}

/** The most enchantment badges a tile draws; the rest are counted, and listed in its tooltip. */
const TILE_ENCHANTMENTS = 3

/** A prize's tile: its item as one element, with the name, enchantments and tooltip of an item taken in whole. */
function PrizeTile({
  data,
  mark,
  name,
  details: values,
  onOpen,
}: {
  data: string
  mark: ReactNode
  name: string
  details: ReactNode
  onOpen: () => void
}) {
  const details = useItemDetails(data)
  return (
    <Tile
      mark={mark}
      name={details.name ?? name}
      badges={<EnchantmentBadges enchantments={details.enchantments} most={TILE_ENCHANTMENTS} />}
      details={values}
      tip={itemTip(details, TILE_ENCHANTMENTS)}
      onOpen={onOpen}
    />
  )
}

/** A prize's dialog: its item with every enchantment and line, then its values, which `children` draws by its name. */
function PrizeDialog({
  data,
  mark,
  name,
  description,
  disabled,
  children,
  onRemove,
  onClose,
}: {
  data: string
  mark: ReactNode
  name: string
  description?: string
  disabled: boolean
  children: (name: string) => ReactNode
  onRemove: () => void
  onClose: () => void
}) {
  const details = useItemDetails(data)
  const shown = details.name ?? name
  return (
    <EntryDialog
      open
      mark={mark}
      name={shown}
      description={description}
      badges={<EnchantmentBadges enchantments={details.enchantments} className="justify-start" />}
      disabled={disabled}
      onRemove={onRemove}
      onClose={onClose}
    >
      <ItemLines lines={details.lines} className="pb-2" />
      {children(shown)}
    </EntryDialog>
  )
}

/** A prize's amount and weight, and the chance they give: the weight over the total weight of the pool, as smp draws. */
function Prize({
  list,
  prize,
  index,
  name,
  total,
  disabled,
  onSet,
}: {
  list: ConfigEntry
  prize: SectionValues
  index: number
  name: string
  total: number
  disabled: boolean
  onSet: (key: string, value: string) => void
}) {
  const id = `${list.path}-${index}`
  const input = (key: string) => (
    <Labelled id={`${id}-${key}`} label={field(list, key)?.label ?? key}>
      <NumberInput
        id={`${id}-${key}`}
        label={`${name} ${field(list, key)?.label ?? key}`}
        entry={field(list, key)}
        value={text(prize, key)}
        disabled={disabled}
        className="w-full"
        onChange={(value) => onSet(key, value)}
      />
    </Labelled>
  )
  return (
    <>
      <div className="grid grid-cols-3 gap-2">
        {input("amount")}
        {input("weight")}
        <Labelled id={`${id}-chance`} label={t("steward.game.chance")}>
          <NumberInput
            id={`${id}-chance`}
            label={`${name} ${t("steward.game.chance")}`}
            value={chance(count(prize, "weight"), total)}
            disabled={disabled}
            readOnly
            className="w-full"
          />
        </Labelled>
      </div>
      <p className="text-xs text-muted-foreground">{t("steward.game.chance-is", { total })}</p>
    </>
  )
}

/** The blocks a player right clicks to open the wheel, each a tile as every place in the settings is. */
function Blocks({
  list,
  blocks,
  disabled,
  onChange,
}: {
  list: ConfigEntry
  blocks: SectionValues[]
  disabled: boolean
  onChange: (next: SectionValues[]) => void
}) {
  return (
    <section className="flex flex-col gap-3">
      <Heading>{list.label}</Heading>
      <PlaceList entry={list} value={blocks} disabled={disabled} onChange={onChange} />
    </section>
  )
}
