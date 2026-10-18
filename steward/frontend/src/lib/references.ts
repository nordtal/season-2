import type { ConfigReference, GameData, GameEntry, GameTag, GuildList, Person, ReferenceKind } from "@/lib/api"
import { message, t } from "@/lib/texts"
import { sentenceOf } from "@/lib/words"

/**
 * What a setting's value names, read against the game catalogue or the guild. Nothing here refuses a value: the game checks it, and an unlisted id is shown as unknown and never replaced.
 */

/**
 * One thing that a picker offers: the value it writes, the name it shows, and where it hangs in a tree.
 */
export type Choice = {
  id: string
  name: string
  icon?: string
  depth?: number
  /**
   * An advancement's frame (`task`, `goal` or `challenge`) and the line under its tooltip title.
   */
  frame?: string
  description?: string
  channel?: "text" | "voice"
}

export type Choices = {
  choices: Choice[]
  tags: GameTag[]
  /** A choice without an item is drawn with the registry's generic icon. */
  registry?: string
  tree: boolean
  /** Set when nothing can be listed. The value is then typed. */
  unavailable?: string
}

const MINECRAFT = "minecraft:"

const REGISTRY: Partial<Record<ReferenceKind, string>> = {
  ITEM: "item",
  BLOCK: "block",
  ENTITY_TYPE: "entity_type",
  ADVANCEMENT: "advancement",
  STATISTIC: "statistic",
  ENCHANTMENT: "enchantment",
  BIOME: "biome",
  MOB_EFFECT: "mob_effect",
  SOUND_EVENT: "sound_event",
  DAMAGE_TYPE: "damage_type",
}

/** The kinds that name no game registry, so the catalogue is not fetched for them. */
const NOT_GAME = new Set<ReferenceKind>(["COLOUR", "DISCORD_CHANNEL", "DISCORD_USER"])

export function isGameReference(reference: ConfigReference): boolean {
  return !NOT_GAME.has(reference.to)
}

export function isColour(reference: ConfigReference | undefined): boolean {
  return reference?.to === "COLOUR"
}

/**
 * Returns the key under which the catalogue files a value: lower case, with `minecraft` where it names no namespace.
 */
export function gameKey(value: string): string {
  const trimmed = value.trim().toLowerCase()
  return trimmed === "" || trimmed.includes(":") ? trimmed : MINECRAFT + trimmed
}

/** `minecraft:oak_log` reads "Oak log" where the game names it nowhere. */
export function nameOfId(id: string): string {
  const path = id.slice(id.indexOf(":") + 1)
  return sentenceOf(path.slice(path.lastIndexOf("/") + 1).split(/[_.]+/)) || id
}

/**
 * Returns the registry that a reference reads, or none. A statistic's subject is the registry that the statistic counts, read from its sibling's value.
 */
export function registryOf(reference: ConfigReference, game: GameData | undefined, sibling?: string): string | null {
  if (reference.to !== "SUBJECT") return REGISTRY[reference.to] ?? null
  if (!game || !sibling) return null
  const statistic = (game.registries.statistic ?? []).find((entry) => entry.id === gameKey(sibling))
  return statistic?.subject ?? null
}

/**
 * Returns whether a reference has nothing to name for its sibling's value, such as the subject of a statistic kept per nothing. Before the catalogue arrives this is not known, so the field stays.
 */
export function namesNothing(reference: ConfigReference, game: GameData | undefined, sibling?: string): boolean {
  return reference.to === "SUBJECT" && game?.version !== undefined && registryOf(reference, game, sibling) === null
}

/**
 * Returns the slot that stands for an entry: its own id for an item, a block or a patterned advancement, else its icon item or egg.
 */
function iconOf(registry: string, entry: GameEntry, items: ReadonlySet<string>): string | undefined {
  const candidates =
    registry === "advancement"
      ? [entry.id, entry.icon]
      : registry === "entity_type"
        ? [`${entry.id}_spawn_egg`]
        : registry === "enchantment"
          ? [`${MINECRAFT}enchanted_book`]
          : [entry.id]
  return candidates.find((id): id is string => id !== undefined && items.has(id))
}

/**
 * Returns an advancement tree flattened in order, with children under their parents. Only advancements with a display are in it.
 */
function advancementTree(entries: GameEntry[], items: ReadonlySet<string>): Choice[] {
  const shown = entries.filter((entry) => entry.frame !== undefined)
  const ids = new Set(shown.map((entry) => entry.id))
  const children = new Map<string, GameEntry[]>()
  const roots: GameEntry[] = []
  for (const entry of shown) {
    if (entry.parent && ids.has(entry.parent)) {
      const siblings = children.get(entry.parent) ?? []
      siblings.push(entry)
      children.set(entry.parent, siblings)
    } else {
      roots.push(entry)
    }
  }
  const out: Choice[] = []
  const walk = (entry: GameEntry, depth: number) => {
    out.push({
      id: entry.id,
      name: nameOf(entry),
      icon: iconOf("advancement", entry, items),
      depth,
      frame: entry.frame,
      description: entry.description?.trim() || undefined,
    })
    for (const child of (children.get(entry.id) ?? []).toSorted(byName)) walk(child, depth + 1)
  }
  for (const root of roots.toSorted(byName)) walk(root, 0)
  return out
}

function byName(a: GameEntry, b: GameEntry): number {
  return nameOf(a).localeCompare(nameOf(b))
}

function nameOf(entry: GameEntry): string {
  return entry.text?.trim() || nameOfId(entry.id)
}

export function gameChoices(game: GameData | undefined, registry: string | null, except: string[] = []): Choices {
  if (!game?.version)
    return {
      choices: [],
      tags: [],
      tree: false,
      unavailable: "No server has published its game data yet.",
    }
  if (registry === null) return { choices: [], tags: [], tree: false }
  const left = new Set(except.map(gameKey))
  const entries = (game.registries[registry] ?? []).filter((entry) => !left.has(entry.id))
  const items = new Set(Object.keys(game.icons?.slots ?? {}))
  const tags = game.tags[registry] ?? []
  if (registry === "advancement")
    return {
      choices: advancementTree(entries, items),
      tags,
      tree: true,
      registry,
    }
  const choices = entries
    .map((entry) => ({
      id: entry.id,
      name: nameOf(entry),
      icon: iconOf(registry, entry, items),
    }))
    .toSorted((a, b) => a.name.localeCompare(b.name))
  return { choices, tags, tree: false, registry }
}

export function guildChoices(list: GuildList | undefined): Choices {
  if (!list)
    return {
      choices: [],
      tags: [],
      tree: false,
      unavailable: "Loading the guild.",
    }
  if (!list.available)
    return {
      choices: [],
      tags: [],
      tree: false,
      unavailable: list.reason ? message(list.reason) : t("steward.said.guild-not-listed"),
    }
  /** A category is never a place where a message goes. */
  const choices = list.entries
    .filter((entry) => entry.type !== 4)
    .map((entry): Choice => {
      const voice = entry.type === 2 || entry.type === 13
      return {
        id: entry.id,
        name: entry.name,
        channel: voice ? "voice" : "text",
      }
    })
  return { choices, tags: [], tree: false }
}

export function peopleChoices(people: Person[] | undefined): Choices {
  if (!people)
    return {
      choices: [],
      tags: [],
      tree: false,
      unavailable: "Loading the people.",
    }
  const choices = people.map((person) => ({
    id: person.discordId,
    name: person.discordDisplayName ?? person.discordUsername ?? person.mcName ?? person.discordId,
  }))
  return {
    choices: choices.toSorted((a, b) => a.name.localeCompare(b.name)),
    tags: [],
    tree: false,
  }
}

export function choiceFor(choices: Choice[], value: string): Choice | undefined {
  const exact = choices.find((choice) => choice.id === value)
  if (exact) return exact
  const key = gameKey(value)
  return choices.find((choice) => choice.id === key)
}

export function matching(choices: Choice[], query: string): Choice[] {
  const needle = query.trim().toLowerCase()
  if (!needle) return choices
  return choices.filter(
    (choice) => choice.name.toLowerCase().includes(needle) || choice.id.toLowerCase().includes(needle),
  )
}

export function namespacesOf(choices: Choice[]): string[] {
  const found = new Set<string>()
  for (const choice of choices) if (choice.id.includes(":")) found.add(choice.id.slice(0, choice.id.indexOf(":")))
  return [...found].toSorted((a, b) => (a === "minecraft" ? -1 : b === "minecraft" ? 1 : a.localeCompare(b)))
}
