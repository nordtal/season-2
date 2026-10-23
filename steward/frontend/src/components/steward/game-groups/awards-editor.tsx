import { useMemo, useState } from "react"

import type { ConfigDocument, ConfigEntry, ConfigReference } from "@/lib/api"
import { choiceFor, matching, nameOfId } from "@/lib/references"
import { draftSections } from "@/components/steward/configuration"
import type { CustomEditorProps } from "@/components/steward/config-editors"
import { FilterBar, SearchField } from "@/components/steward/filter-bar"
import { GroupEditor } from "@/components/steward/group-draft"
import { Pill } from "@/components/steward/milestones/parts"
import { Mark, useChoices } from "@/components/steward/reference-picker"
import { type SectionValues, blankSection } from "@/components/steward/repeatable-cards"
import { TERM_ICON } from "@/components/steward/term-icons"
import { field, listEntry, sum, text } from "@/components/steward/game-groups/model"
import { AddPicker, BARE, GamePicker, NumberInput, RemoveButton } from "@/components/steward/game-groups/parts"
import { t } from "@/lib/texts"

const KEYS = ["advancement", "aura"]

export function readsAwards(document: ConfigDocument): boolean {
  const list = listEntry(document.entries, KEYS)
  return list !== undefined && field(list, "advancement")?.refers !== undefined
}

export function AwardsEditor({ file, document }: CustomEditorProps) {
  const list = listEntry(document.entries, KEYS)
  if (list === undefined) return null
  return (
    <GroupEditor file={file} document={document}>
      {(draft, set) => (
        <Awards
          list={list}
          awards={draftSections(draft, list)}
          disabled={!document.writable}
          onChange={(next) => set(list.path, next)}
        />
      )}
    </GroupEditor>
  )
}

/** One row per award in stored order, with its aura and spins; the search narrows the rows by name or ID. */
function Awards({
  list,
  awards,
  disabled,
  onChange,
}: {
  list: ConfigEntry
  awards: SectionValues[]
  disabled: boolean
  onChange: (next: SectionValues[]) => void
}) {
  const [query, setQuery] = useState("")
  const reference = field(list, "advancement")!.refers!
  const { choices, icons } = useChoices(reference, undefined)
  const awarded = useMemo(() => awards.map((award) => text(award, "advancement")), [awards])
  const unawarded: ConfigReference = useMemo(
    () => ({ ...reference, except: [...(reference.except ?? []), ...awarded] }),
    [reference, awarded],
  )
  const shown = useMemo(() => {
    const rows = awards.map((award, index) => {
      const id = text(award, "advancement")
      const choice = choiceFor(choices.choices, id)
      return { award, index, id, choice, name: choice?.name ?? nameOfId(id) }
    })
    if (query.trim() === "") return rows
    const hits = new Set(
      matching(
        rows.map((row) => ({ id: row.id, name: row.name })),
        query,
      ).map((hit) => hit.id),
    )
    return rows.filter((row) => hits.has(row.id))
  }, [awards, choices.choices, query])

  const update = (index: number, key: string, value: string) =>
    onChange(awards.map((award, at) => (at === index ? { ...award, [key]: value } : award)))
  const label = field(list, "advancement")!.label

  return (
    <div className="@container flex flex-col gap-3">
      <FilterBar>
        <Pill title={label}>
          <TERM_ICON.advancement aria-label={label} className="size-3.5 shrink-0" />
          {awards.length.toLocaleString("en")}
        </Pill>
        <Pill title={field(list, "aura")?.label}>
          <TERM_ICON.aura aria-label={field(list, "aura")?.label} className="size-3.5 shrink-0" />
          {sum(awards, "aura").toLocaleString("en")}
        </Pill>
        {field(list, "spins") ? (
          <Pill title={field(list, "spins")?.label}>
            <TERM_ICON.spins aria-label={field(list, "spins")?.label} className="size-3.5 shrink-0" />
            {sum(awards, "spins").toLocaleString("en")}
          </Pill>
        ) : null}
        <SearchField
          value={query}
          onValueChange={setQuery}
          label={t("steward.settings.search-in", { what: list.label })}
        />
        <AddPicker
          id={`${list.path}-add`}
          label={t("steward.game.advancement")}
          reference={unawarded}
          disabled={disabled}
          onPick={(id) => onChange([...awards, { ...blankSection(list.template ?? []), advancement: id }])}
        />
      </FilterBar>
      <ul className="flex flex-col divide-y rounded-lg border">
        {shown.map(({ award, index, id, choice, name }) => (
          <li
            key={`${id}-${index}`}
            className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 py-1.5 pr-1.5 pl-1 @md:flex-nowrap"
          >
            <GamePicker
              id={`${list.path}-${index}`}
              label={label}
              reference={reference}
              value={id}
              disabled={disabled}
              className={BARE}
              face={
                <>
                  {choice ? <Mark choice={choice} icons={icons} registry={choices.registry} size={28} /> : null}
                  <span className="flex min-w-0 flex-col items-start text-left">
                    <span className="max-w-full truncate">{name}</span>
                    <span className="max-w-full truncate font-mono text-xs text-muted-foreground">{id}</span>
                  </span>
                  {choice?.description ? (
                    <span className="ml-auto hidden max-w-[45%] truncate text-xs text-muted-foreground @2xl:block">
                      {choice.description}
                    </span>
                  ) : null}
                </>
              }
              onPick={(next) => update(index, "advancement", next)}
            />
            {/* A narrow row puts the fields on a line of their own, under the name. */}
            <div className="order-last flex w-full shrink-0 gap-2 pl-12 @md:order-none @md:w-auto @md:pl-0">
              <NumberInput
                label={t("steward.game.aura-of", { name })}
                term="aura"
                value={text(award, "aura")}
                disabled={disabled}
                className="w-20"
                onChange={(value) => update(index, "aura", value)}
              />
              {field(list, "spins") ? (
                <NumberInput
                  label={t("steward.game.spins-of", { name })}
                  term="spins"
                  value={text(award, "spins")}
                  disabled={disabled}
                  className="w-20"
                  onChange={(value) => update(index, "spins", value)}
                />
              ) : null}
            </div>
            <RemoveButton
              name={name}
              disabled={disabled}
              onRemove={() => onChange(awards.filter((_, at) => at !== index))}
            />
          </li>
        ))}
      </ul>
    </div>
  )
}
