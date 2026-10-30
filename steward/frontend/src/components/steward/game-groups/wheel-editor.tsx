import { PlusIcon, XIcon } from "@phosphor-icons/react"
import { Fragment, type ReactNode, useState } from "react"

import type { ConfigDocument, ConfigEntry } from "@/lib/api"
import { choiceFor, nameOfId } from "@/lib/references"
import { draftSections } from "@/components/steward/configuration"
import type { CustomEditorProps } from "@/components/steward/config-editors"
import { GroupEditor } from "@/components/steward/group-draft"
import { Pill } from "@/components/steward/milestones/parts"
import { Mark, useChoices } from "@/components/steward/reference-picker"
import { type SectionValues, blankSection } from "@/components/steward/repeatable-cards"
import { TERM_ICON } from "@/components/steward/term-icons"
import { BOX_KEYS, chance, count, field, listEntry, sum, text } from "@/components/steward/game-groups/model"
import {
  AddPicker,
  BARE,
  BoxFields,
  GamePicker,
  Heading,
  NumberInput,
  RemoveButton,
} from "@/components/steward/game-groups/parts"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import { cn } from "@/lib/utils"
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

/** A tile per prize shows its item, amount and chance; the chosen tile opens its fields in the row below it. */
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
  const face = (id: string) => {
    const choice = choiceFor(choices.choices, id)
    return (
      <>
        {choice ? <Mark choice={choice} icons={icons} registry={choices.registry} size={28} /> : null}
        <span className="truncate">{nameOf(id)}</span>
      </>
    )
  }
  const open = chosen !== null && chosen < prizes.length ? chosen : null

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
        <Pill tip={item.label}>
          <TERM_ICON.item aria-label={item.label} className="size-3.5 shrink-0" />
          {prizes.length.toLocaleString("en")}
        </Pill>
        <Pill>{t("steward.game.total-weight", { total })}</Pill>
      </div>
      {/* The panel follows its tile and spans a whole row, so it lands below the tile's row and the dense flow fills that row. */}
      <ul className="grid grid-flow-row-dense grid-cols-3 gap-2 @lg:grid-cols-4 @2xl:grid-cols-5 @4xl:grid-cols-6">
        {prizes.map((prize, index) => {
          const id = text(prize, "item")
          const choice = choiceFor(choices.choices, id)
          return (
            <Fragment key={index}>
              <li>
                <button
                  type="button"
                  aria-pressed={open === index}
                  className={cn(
                    "flex w-full min-w-0 flex-col items-center gap-1 rounded-lg border px-1.5 py-2.5 text-center transition-colors hover:bg-muted/50",
                    open === index && "border-primary bg-primary/8",
                  )}
                  onClick={() => choose(open === index ? null : index)}
                >
                  <span className="relative">
                    {choice ? (
                      <Mark choice={choice} icons={icons} registry={choices.registry} size={40} />
                    ) : (
                      <TERM_ICON.warning aria-hidden className="size-10 text-warning" />
                    )}
                    <span className="absolute -right-2 -bottom-1 font-mono text-xs font-semibold tabular-nums">
                      {count(prize, "amount").toLocaleString("en")}
                    </span>
                  </span>
                  <span className="w-full truncate text-xs">{nameOf(id)}</span>
                  <span className="text-sm font-medium tabular-nums">{chance(count(prize, "weight"), total)}</span>
                </button>
              </li>
              {open === index ? (
                <li className="col-span-full">
                  <Prize
                    list={list}
                    prize={prize}
                    index={index}
                    name={nameOf(id)}
                    face={face(id)}
                    total={total}
                    disabled={disabled}
                    onSet={(key, value) => update(index, key, value)}
                    onRemove={() => {
                      onChange(prizes.filter((_, at) => at !== index))
                      choose(null)
                    }}
                    onClose={() => choose(null)}
                  />
                </li>
              ) : null}
            </Fragment>
          )
        })}
      </ul>
    </section>
  )
}

function Prize({
  list,
  prize,
  index,
  name,
  face,
  total,
  disabled,
  onSet,
  onRemove,
  onClose,
}: {
  list: ConfigEntry
  prize: SectionValues
  index: number
  name: string
  face: ReactNode
  total: number
  disabled: boolean
  onSet: (key: string, value: string) => void
  onRemove: () => void
  onClose: () => void
}) {
  const item = field(list, "item")!
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
    <div className="flex flex-col gap-3 rounded-lg border border-primary p-3">
      <div className="flex items-center gap-1">
        <GamePicker
          id={`${id}-item`}
          label={item.label}
          reference={item.refers!}
          value={text(prize, "item")}
          disabled={disabled}
          className={cn(BARE, "font-medium")}
          face={face}
          onPick={(value) => onSet("item", value)}
        />
        <RemoveButton name={name} disabled={disabled} onRemove={onRemove} />
        <Button type="button" variant="ghost" size="icon-sm" aria-label={t("steward.form.close")} onClick={onClose}>
          <XIcon aria-hidden />
        </Button>
      </div>
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
    </div>
  )
}

function Labelled({ id, label, children }: { id: string; label: string; children: ReactNode }) {
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <Label htmlFor={id} className="text-xs font-normal text-muted-foreground">
        {label}
      </Label>
      {children}
    </div>
  )
}

function regionName(index: number): string {
  return `${t("steward.game.region")} ${index + 1}`
}

/** The wheel's blocks, each a world and two corners. */
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
    <section className="flex max-w-xl flex-col gap-3">
      <Heading
        actions={
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={disabled}
            onClick={() => onChange([...regions, blankSection(list.template ?? [])])}
          >
            <PlusIcon aria-hidden />
            {t("steward.game.region")}
          </Button>
        }
      >
        {list.label}
      </Heading>
      <ul className="flex flex-col divide-y rounded-lg border">
        {regions.map((region, index) => (
          <li key={index} className="flex items-start gap-2 p-3">
            <div className="min-w-0 flex-1">
              <BoxFields
                id={`${list.path}-${index}`}
                list={list}
                section={region}
                disabled={disabled}
                onChange={(key, value) =>
                  onChange(regions.map((entry, at) => (at === index ? { ...entry, [key]: value } : entry)))
                }
              />
            </div>
            <RemoveButton
              name={regionName(index)}
              disabled={disabled}
              onRemove={() => onChange(regions.filter((_, at) => at !== index))}
            />
          </li>
        ))}
      </ul>
    </section>
  )
}
