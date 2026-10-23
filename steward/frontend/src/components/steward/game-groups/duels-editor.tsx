import type { ConfigDocument, ConfigEntry } from "@/lib/api"
import { choiceFor, nameOfId } from "@/lib/references"
import { draftSections } from "@/components/steward/configuration"
import type { CustomEditorProps } from "@/components/steward/config-editors"
import { GroupEditor } from "@/components/steward/group-draft"
import { Mark, useChoices } from "@/components/steward/reference-picker"
import { type SectionValues, blankSection } from "@/components/steward/repeatable-cards"
import { SettingField } from "@/components/steward/settings-config"
import { BOX_KEYS, field, holds, listEntry, text } from "@/components/steward/game-groups/model"
import {
  AddPicker,
  BARE,
  BoxFields,
  GamePicker,
  Heading,
  NumberInput,
  RemoveButton,
} from "@/components/steward/game-groups/parts"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { cn } from "@/lib/utils"
import { t } from "@/lib/texts"

const LOADOUT = "duel-loadout-"
const LOADOUT_KEYS = ["item", "amount"]

/** One loadout per duel type, named by its key: `duel-loadout-sword` is the loadout of type SWORD. */
function loadouts(entries: ConfigEntry[]): ConfigEntry[] {
  return entries.filter((entry) => entry.key.startsWith(LOADOUT) && holds(entry, LOADOUT_KEYS))
}

export function readsDuels(document: ConfigDocument): boolean {
  return listEntry(document.entries, ["type", ...BOX_KEYS]) !== undefined && loadouts(document.entries).length > 0
}

export function DuelsEditor({ file, document }: CustomEditorProps) {
  const platforms = listEntry(document.entries, ["type", ...BOX_KEYS])
  if (platforms === undefined) return null
  const arena = document.entries.filter((entry) => entry.kind !== "SECTIONS")
  const disabled = !document.writable
  return (
    <GroupEditor file={file} document={document}>
      {(draft, set) => {
        const stands = draftSections(draft, platforms)
        return (
          <>
            <section className="flex flex-col gap-3">
              <Heading>{t("steward.game.arena")}</Heading>
              <div className="grid grid-cols-[repeat(auto-fill,minmax(10rem,1fr))] gap-4">
                {arena.map((entry) => (
                  <SettingField
                    key={entry.path}
                    entry={entry}
                    layout="full"
                    writable={document.writable}
                    draft={draft}
                    dirty={Object.hasOwn(draft, entry.path)}
                    highlight={null}
                    onChange={(value) => set(entry.path, value)}
                    onUndo={() => set(entry.path, undefined)}
                  />
                ))}
              </div>
            </section>
            <section className="flex flex-col gap-3">
              <Heading>{t("steward.game.duel-types")}</Heading>
              <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
                {loadouts(document.entries).map((loadout) => (
                  <DuelType
                    key={loadout.path}
                    type={loadout.key.slice(LOADOUT.length).toUpperCase()}
                    platforms={platforms}
                    stands={stands}
                    loadout={loadout}
                    items={draftSections(draft, loadout)}
                    disabled={disabled}
                    onPlatforms={(next) => set(platforms.path, next)}
                    onItems={(next) => set(loadout.path, next)}
                  />
                ))}
              </div>
            </section>
          </>
        )
      }}
    </GroupEditor>
  )
}

/** A duel type's platforms and the items each player gets. The platforms stay in one list; the card edits those of its type. */
function DuelType({
  type,
  platforms,
  stands,
  loadout,
  items,
  disabled,
  onPlatforms,
  onItems,
}: {
  type: string
  platforms: ConfigEntry
  stands: SectionValues[]
  loadout: ConfigEntry
  items: SectionValues[]
  disabled: boolean
  onPlatforms: (next: SectionValues[]) => void
  onItems: (next: SectionValues[]) => void
}) {
  const item = field(loadout, "item")!
  const { choices, icons } = useChoices(item.refers!, undefined)
  const nameOf = (id: string) => choiceFor(choices.choices, id)?.name ?? nameOfId(id)
  const first = choiceFor(choices.choices, text(items[0] ?? {}, "item"))
  const title = nameOfId(type.toLowerCase())
  const updateItem = (index: number, key: string, value: string) =>
    onItems(items.map((entry, at) => (at === index ? { ...entry, [key]: value } : entry)))

  return (
    <Card size="sm">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          {first ? <Mark choice={first} icons={icons} registry={choices.registry} size={28} /> : null}
          {title}
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {stands.map((stand, index) =>
          text(stand, "type") === type ? (
            <div key={index} className="flex flex-col gap-1.5">
              <span className="text-xs text-muted-foreground">{t("steward.game.platform")}</span>
              <BoxFields
                id={`${platforms.path}-${index}`}
                list={platforms}
                section={stand}
                disabled={disabled}
                onChange={(key, value) =>
                  onPlatforms(stands.map((entry, at) => (at === index ? { ...entry, [key]: value } : entry)))
                }
              />
            </div>
          ) : null,
        )}
        <div className="flex flex-col gap-1.5">
          <span className="text-xs text-muted-foreground">{t("steward.game.loadout")}</span>
          <ul className="flex flex-col divide-y">
            {items.map((entry, index) => {
              const id = text(entry, "item")
              const choice = choiceFor(choices.choices, id)
              const name = nameOf(id)
              return (
                <li key={`${id}-${index}`} className="flex min-w-0 items-center gap-2 py-1">
                  <GamePicker
                    id={`${loadout.path}-${index}`}
                    label={item.label}
                    reference={item.refers!}
                    value={id}
                    disabled={disabled}
                    className={cn(BARE, "px-1")}
                    face={
                      <>
                        {choice ? <Mark choice={choice} icons={icons} registry={choices.registry} size={24} /> : null}
                        <span className="truncate">{name}</span>
                      </>
                    }
                    onPick={(next) => updateItem(index, "item", next)}
                  />
                  <NumberInput
                    label={`${name} ${field(loadout, "amount")?.label ?? ""}`.trim()}
                    entry={field(loadout, "amount")}
                    value={text(entry, "amount")}
                    disabled={disabled}
                    className="w-20"
                    onChange={(value) => updateItem(index, "amount", value)}
                  />
                  <RemoveButton
                    name={name}
                    disabled={disabled}
                    onRemove={() => onItems(items.filter((_, at) => at !== index))}
                  />
                </li>
              )
            })}
          </ul>
          <div>
            <AddPicker
              id={`${loadout.path}-add`}
              label={t("steward.game.item")}
              reference={item.refers!}
              disabled={disabled}
              onPick={(next) => onItems([...items, { ...blankSection(loadout.template ?? []), item: next }])}
            />
          </div>
        </div>
      </CardContent>
    </Card>
  )
}
