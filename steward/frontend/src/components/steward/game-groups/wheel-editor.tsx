import { useState } from "react"

import type { ConfigDocument, ConfigEntry } from "@/lib/api"
import { choiceFor, nameOfId } from "@/lib/references"
import { draftSections } from "@/components/steward/configuration"
import type { CustomEditorProps } from "@/components/steward/config-editors"
import { GroupEditor } from "@/components/steward/group-draft"
import { Pill } from "@/components/steward/milestones/parts"
import { Empty } from "@/components/steward/query-state"
import { Mark, useChoices } from "@/components/steward/reference-picker"
import { type SectionValues, blankSection } from "@/components/steward/repeatable-cards"
import { TERM_ICON } from "@/components/steward/term-icons"
import { BOX_KEYS, chance, count, field, listEntry, sum, text } from "@/components/steward/game-groups/model"
import { AddPicker, Heading, NumberInput } from "@/components/steward/game-groups/parts"
import { PlaceList } from "@/components/steward/game-groups/places"
import { EntryDialog, Labelled, Tile, Tiles } from "@/components/steward/game-groups/tiles"
import { t } from "@/lib/texts"

const KEYS = ["item", "amount", "weight"]

export function readsWheel(document: ConfigDocument): boolean {
  const list = listEntry(document.entries, KEYS)
  return list !== undefined && field(list, "item")?.refers !== undefined
}

export function WheelEditor({ file, document }: CustomEditorProps) {
  const prizes = listEntry(document.entries, KEYS)
  const regions = listEntry(document.entries, BOX_KEYS)
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
          {regions ? (
            <Regions
              list={regions}
              regions={draftSections(draft, regions)}
              disabled={disabled}
              onChange={(next) => set(regions.path, next)}
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
            onPick={(id) => {
              onChange([...prizes, { ...blankSection(list.template ?? []), item: id }])
              choose(prizes.length)
            }}
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
            <Tile
              key={index}
              mark={mark(entry, 40)}
              name={nameOf(text(entry, "item"))}
              details={chance(count(entry, "weight"), total)}
              onOpen={() => choose(index)}
            />
          ))}
        </Tiles>
      )}
      {open !== null && prize !== undefined ? (
        <EntryDialog
          open
          mark={mark(prize, 48)}
          name={nameOf(text(prize, "item"))}
          description={choiceFor(choices.choices, text(prize, "item"))?.description}
          disabled={disabled}
          onRemove={() => onChange(prizes.filter((_, at) => at !== open))}
          onClose={() => choose(null)}
        >
          <Prize
            list={list}
            prize={prize}
            index={open}
            name={nameOf(text(prize, "item"))}
            total={total}
            disabled={disabled}
            onSet={(key, value) => update(open, key, value)}
          />
        </EntryDialog>
      ) : null}
    </section>
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

/** The wheel's places, drawn as every place in the settings is. */
function Regions({
  list,
  regions,
  disabled,
  onChange,
}: {
  list: ConfigEntry
  regions: SectionValues[]
  disabled: boolean
  onChange: (next: SectionValues[]) => void
}) {
  return (
    <section className="flex flex-col gap-3">
      <Heading>{list.label}</Heading>
      <PlaceList entry={list} value={regions} disabled={disabled} onChange={onChange} />
    </section>
  )
}
