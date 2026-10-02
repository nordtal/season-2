import type { ConfigReference, GameData, GameEntry, GameTag, GuildList, Person, ReferenceKind } from "@/lib/api"
import { sentenceOf } from "@/lib/words"

/**
 * What a setting's value names, read against the servers' game catalogue or the guild, as plain data.
 *
 * Nothing here refuses a value: the game checks it, and an id nobody lists is shown as unknown, never replaced.
 */

/** One thing a picker offers: the value it writes, the name it shows, and where it hangs in a tree. */
export type Choice = {
  id: string
  name: string
  /** The item whose icon stands for it, if the catalogue has one. */
  icon?: string
  /** Its depth in a tree, 0 for a root; only an advancement has one. */
  depth?: number
  /** An advancement's frame, `task`, `goal` or `challenge`, and the line its tooltip shows under its title. */
  frame?: string
  description?: string
  /** A Discord channel's kind, which its row draws as an icon. */
  channel?: "text" | "voice"
}

/** The choices for one reference, or why there are none to offer. */
export type Choices = {
  choices: Choice[]
  tags: GameTag[]
  /** Advancements are offered as their tree. */
  tree: boolean
  /** Set when nothing can be listed, and the value is typed instead. */
  unavailable?: string
}

const MINECRAFT = "minecraft:"

/** A registry's name in the catalogue, for the kinds that are one. */
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

/** Whether `kind` is drawn by the colour control rather than a list. */
export function isColour(reference: ConfigReference | undefined): boolean {
  return reference?.to === "COLOUR"
}

/** A value as the key the catalogue files it under: lower case, `minecraft` where it names no namespace. */
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
 * The registry a reference reads, or none where it is no game registry.
 *
 * A statistic's subject is the registry that statistic counts, read off its sibling's value.
 */
export function registryOf(reference: ConfigReference, game: GameData | undefined, sibling?: string): string | null {
  if (reference.to !== "SUBJECT") return REGISTRY[reference.to] ?? null
  if (!game || !sibling) return null
  const statistic = (game.registries.statistic ?? []).find((entry) => entry.id === gameKey(sibling))
  return statistic?.subject ?? null
}

/** The item standing for an entry: its own id for an item or a block, an advancement's icon, a mob's egg. */
function iconOf(registry: string, entry: GameEntry, items: ReadonlySet<string>): string | undefined {
  const candidates =
    registry === "advancement"
      ? [entry.icon]
      : registry === "entity_type"
        ? [`${entry.id}_spawn_egg`]
        : registry === "enchantment"
          ? [`${MINECRAFT}enchanted_book`]
          : [entry.id]
  return candidates.find((id): id is string => id !== undefined && items.has(id))
}

/** An advancement tree flattened in order, holding only those with a display, children by name under parents. */
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

/** What a game registry offers, by name. */
export function gameChoices(game: GameData | undefined, registry: string | null): Choices {
  if (!game?.version)
    return { choices: [], tags: [], tree: false, unavailable: "No server has published its game data yet." }
  if (registry === null) return { choices: [], tags: [], tree: false }
  const entries = game.registries[registry] ?? []
  const items = new Set(Object.keys(game.icons?.slots ?? {}))
  const tags = game.tags[registry] ?? []
  if (registry === "advancement") return { choices: advancementTree(entries, items), tags, tree: true }
  const choices = entries
    .map((entry) => ({ id: entry.id, name: nameOf(entry), icon: iconOf(registry, entry, items) }))
    .toSorted((a, b) => a.name.localeCompare(b.name))
  return { choices, tags, tree: false }
}

/** What a guild list offers, channels under their category. */
export function guildChoices(list: GuildList | undefined, what: "role" | "channel"): Choices {
  if (!list) return { choices: [], tags: [], tree: false, unavailable: "Loading the guild." }
  if (!list.available)
    return { choices: [], tags: [], tree: false, unavailable: list.reason ?? "The guild cannot be listed." }
  if (what === "role") {
    return { choices: list.entries.map((entry) => ({ id: entry.id, name: entry.name })), tags: [], tree: false }
  }
  /** A category holds channels and is never one a message goes to. */
  const choices = list.entries
    .filter((entry) => entry.type !== 4)
    .map((entry): Choice => {
      const voice = entry.type === 2 || entry.type === 13
      return { id: entry.id, name: entry.name, channel: voice ? "voice" : "text" }
    })
  return { choices, tags: [], tree: false }
}

/** The people Steward knows, by their Discord name. */
export function peopleChoices(people: Person[] | undefined): Choices {
  if (!people) return { choices: [], tags: [], tree: false, unavailable: "Loading the people." }
  const choices = people.map((person) => ({
    id: person.discordId,
    name: person.discordDisplayName ?? person.discordUsername ?? person.mcName ?? person.discordId,
  }))
  return { choices: choices.toSorted((a, b) => a.name.localeCompare(b.name)), tags: [], tree: false }
}

/** The choice a stored value names, matched as a game key where the choices are keys. */
export function choiceFor(choices: Choice[], value: string): Choice | undefined {
  const exact = choices.find((choice) => choice.id === value)
  if (exact) return exact
  const key = gameKey(value)
  return choices.find((choice) => choice.id === key)
}

/** The choices matching `query` by name or id, in any case. */
export function matching(choices: Choice[], query: string): Choice[] {
  const needle = query.trim().toLowerCase()
  if (!needle) return choices
  return choices.filter(
    (choice) => choice.name.toLowerCase().includes(needle) || choice.id.toLowerCase().includes(needle),
  )
}

/** The namespaces among the choices' ids, `minecraft` first. */
export function namespacesOf(choices: Choice[]): string[] {
  const found = new Set<string>()
  for (const choice of choices) if (choice.id.includes(":")) found.add(choice.id.slice(0, choice.id.indexOf(":")))
  return [...found].toSorted((a, b) => (a === "minecraft" ? -1 : b === "minecraft" ? 1 : a.localeCompare(b)))
}
