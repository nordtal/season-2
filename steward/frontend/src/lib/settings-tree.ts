import type { ConfigEntry, DocumentView, MessageEntry, MessageText, PluginFile } from "@/lib/api"
import { documentId, pageCards, type PageCard } from "@/lib/documents"
import { entryHaystack } from "@/lib/settings-search"
import { groupOf, PAGE_GROUPS, pageGroupOf, TEXT_GROUPS, type Places } from "@/lib/text-places"
import { choice, t } from "@/lib/texts"
import { sentenceOf } from "@/lib/words"
import { colourRuns } from "@/components/steward/colour-control"
import { pairedBlocks, pairedPaths, type PairedBlocks } from "@/components/steward/paired-blocks"

/**
 * One leaf of a file's settings tree, built as data so a test can check its shape. Keys stay the identity: a leaf's `ids` are what a draft, a search hit and a jump name.
 */
export type TreeLeaf<L> = {
  kind: "leaf"
  id: string
  /**
   * Every key that this leaf draws: one for a field, several for a colour run or a paired block.
   */
  ids: string[]
  value: L
  wide: boolean
}

export type TreeBranch<L> = {
  kind: "branch"
  id: string
  /** One name, or several when single-child branches were folded into one row. */
  labels: string[]
  children: TreeNode<L>[]
}

export type TreeNode<L> = TreeLeaf<L> | TreeBranch<L>

/**
 * `farm-world` reads "Farm world" and `serverUuid` reads "Server UUID", for a section that nothing named.
 */
export function humanise(segment: string): string {
  return sentenceOf(segment.split(/[-_.\s]+/)) || segment
}

class Builder<L> {
  readonly root: TreeNode<L>[] = []
  private readonly branches = new Map<string, TreeBranch<L>>()

  branch(id: string, label: string, parent: string | null): TreeBranch<L> {
    const known = this.branches.get(id)
    if (known) return known
    const branch: TreeBranch<L> = { kind: "branch", id, labels: [label], children: [] }
    this.branches.set(id, branch)
    this.childrenOf(parent).push(branch)
    return branch
  }

  has(id: string): boolean {
    return this.branches.has(id)
  }

  add(parent: string | null, node: TreeNode<L>) {
    this.childrenOf(parent).push(node)
  }

  private childrenOf(parent: string | null): TreeNode<L>[] {
    if (parent === null) return this.root
    const branch = this.branches.get(parent)
    if (!branch) throw new Error(`no branch ${parent}`)
    return branch.children
  }

  /**
   * Returns the tree. A branch with one branch child becomes one row with both names, and a lone top section is not a root.
   */
  build(): TreeNode<L>[] {
    let nodes = this.root.map(fold)
    while (nodes.length === 1 && nodes[0].kind === "branch") nodes = nodes[0].children
    return nodes
  }
}

function fold<L>(node: TreeNode<L>): TreeNode<L> {
  if (node.kind === "leaf") return node
  let branch: TreeBranch<L> = { ...node, labels: [...node.labels] }
  while (branch.children.length === 1 && branch.children[0].kind === "branch") {
    const only = branch.children[0]
    branch = { kind: "branch", id: only.id, labels: [...branch.labels, ...only.labels], children: only.children }
  }
  return { ...branch, children: branch.children.map(fold) }
}

function parentPath(path: string): string | null {
  const at = path.lastIndexOf(".")
  return at < 0 ? null : path.slice(0, at)
}

export type ConfigLeafValue =
  | { kind: "entry"; entry: ConfigEntry }
  | { kind: "run"; entries: ConfigEntry[] }
  | { kind: "pair"; pair: PairedBlocks }

function wideEntry(entry: ConfigEntry): boolean {
  return entry.kind === "LIST" || entry.kind === "SECTIONS" || !entry.editable || (entry.value ?? "").includes("\n")
}

export function configTree(entries: ConfigEntry[]): TreeNode<ConfigLeafValue>[] {
  const builder = new Builder<ConfigLeafValue>()
  const pairs = pairedBlocks(entries)
  const paired = pairedPaths(pairs)
  const runs = colourRuns(entries.filter((entry) => entry.kind !== "MAP" && !paired.has(entry.path)))
  const runOf = new Map<string, ConfigEntry[]>()
  for (const run of runs) for (const member of run) runOf.set(member.path, run)
  const labelOf = new Map(entries.filter((entry) => entry.kind === "MAP").map((entry) => [entry.path, entry.label]))

  /**
   * Gives a section that no MAP lists a branch of its own, so every leaf has somewhere to hang.
   */
  const ensure = (path: string | null): string | null => {
    if (path === null) return null
    if (!builder.has(path)) {
      const parent = ensure(parentPath(path))
      builder.branch(path, labelOf.get(path) ?? humanise(path.slice(path.lastIndexOf(".") + 1)), parent)
    }
    return path
  }

  for (const entry of entries) {
    const pair = pairs.find((candidate) => candidate.left.path === entry.path)
    if (pair) {
      const ids = pair.rows.flatMap((row) => [row.left.path, row.right.path])
      builder.add(ensure(parentPath(entry.path)), {
        kind: "leaf",
        id: `${pair.left.path}+${pair.right.path}`,
        ids,
        value: { kind: "pair", pair },
        wide: true,
      })
      continue
    }
    if (paired.has(entry.path)) continue
    if (entry.kind === "MAP") {
      ensure(entry.path)
      continue
    }
    const run = runOf.get(entry.path)
    if (run) {
      if (run[0] !== entry) continue
      builder.add(ensure(parentPath(entry.path)), {
        kind: "leaf",
        id: run.map((member) => member.path).join("+"),
        ids: run.map((member) => member.path),
        value: { kind: "run", entries: run },
        wide: true,
      })
      continue
    }
    builder.add(ensure(parentPath(entry.path)), {
      kind: "leaf",
      id: entry.path,
      ids: [entry.path],
      value: { kind: "entry", entry },
      wide: wideEntry(entry),
    })
  }
  return builder.build()
}

export function configLeafMatches(value: ConfigLeafValue, query: string): boolean {
  const needle = query.trim().toLowerCase()
  if (!needle) return true
  const entries =
    value.kind === "entry"
      ? [value.entry]
      : value.kind === "run"
        ? value.entries
        : [value.pair.left, value.pair.right, ...value.pair.rows.flatMap((row) => [row.left, row.right])]
  return entries.some((entry) => entryHaystack(entry).includes(needle))
}

/** A plugin file in the settings list: `alone` when it is the plugin's only file, which the row names by the plugin. */
export type PluginFileLeaf = { file: PluginFile; label: string; alone: boolean }

/** Returns what a plugin is listed by: its name, else its data folder made readable. */
export function pluginLabel(file: PluginFile): string {
  return file.name ?? humanise(file.plugin)
}

/** Returns the id a plugin file is selected, drafted and named in `?file=` by. */
export function pluginFileId(file: PluginFile): string {
  return `plugin:${file.plugin}/${file.path}`
}

/**
 * Returns the plugins' files by plugin name. A plugin with one file is one row; one with several is a branch whose children mirror its folders, a folder with one folder in it folded into one row.
 */
export function pluginFileTree(files: PluginFile[]): TreeNode<PluginFileLeaf>[] {
  const byPlugin = new Map<string, PluginFile[]>()
  for (const file of files) byPlugin.set(file.plugin, [...(byPlugin.get(file.plugin) ?? []), file])
  const plugins = [...byPlugin.values()].toSorted((a, b) => pluginLabel(a[0]).localeCompare(pluginLabel(b[0])))
  const builder = new Builder<PluginFileLeaf>()
  for (const own of plugins) {
    const label = pluginLabel(own[0])
    if (own.length === 1) {
      const id = pluginFileId(own[0])
      builder.add(null, { kind: "leaf", id, ids: [id], value: { file: own[0], label, alone: true }, wide: false })
      continue
    }
    const root = builder.branch(`plugin:${own[0].plugin}`, label, null).id
    for (const file of own.toSorted((a, b) => a.path.localeCompare(b.path))) {
      const segments = file.path.split("/")
      let parent = root
      for (const folder of segments.slice(0, -1)) parent = builder.branch(`${parent}/${folder}`, folder, parent).id
      const id = pluginFileId(file)
      builder.add(parent, {
        kind: "leaf",
        id,
        ids: [id],
        value: { file, label: segments.at(-1) ?? file.path, alone: false },
        wide: false,
      })
    }
  }
  // The plugin's own row is never folded into a folder, so only its children are.
  return builder.root.map((node) => (node.kind === "leaf" ? node : { ...node, children: node.children.map(fold) }))
}

/**
 * Returns where a text sits on the Translations page, `<bundle>/<key>`. A key alone is not unique across bundles.
 */
export function textId(entry: MessageEntry): string {
  return `${entry.bundle}/${entry.key}`
}

/**
 * Returns the Translations page as a tree: a branch per group, then a document's area (a menu's under the game) or a text's topic. Bundles that share a topic share its branch, and a leaf's `ids` are the texts it shows.
 */
export function textsTree(
  texts: MessageText[],
  places: Places,
  views: readonly DocumentView[] = [],
  service?: string,
): TreeNode<PageCard>[] {
  const builder = new Builder<PageCard>()
  const grouped = pageCards(texts, views, service)
    .map((card) => ({
      card,
      group:
        card.kind === "text"
          ? groupOf(card.text.entry, places)
          : card.view.game
            ? ("GAME" as const)
            : ("DISCORD" as const),
    }))
    .map((each) => ({ ...each, page: each.card.kind === "text" ? pageGroupOf(each.card.text.entry) : undefined }))
    .toSorted(
      (a, b) => TEXT_GROUPS.indexOf(a.group) - TEXT_GROUPS.indexOf(b.group) || pageRank(a.page) - pageRank(b.page),
    )
  for (const { card, group, page } of grouped) {
    const root = builder.branch(group, t("steward.texts.group", { group: choice(group) }), null).id
    // A top topic is named by its label, so a document's area and the texts of the same topic share one branch. A page is a branch of its own.
    const top = (label: string) =>
      builder.branch(page === undefined ? `${group}:@${label}` : `${group}:page:${page}`, label, root).id
    if (card.kind === "document") {
      const id = `document:${documentId(card.view.declaration)}`
      builder.add(top(card.view.declaration.area), { kind: "leaf", id, ids: [id], value: card, wide: true })
      continue
    }
    const entry = card.text.entry
    const segments = entry.key.split(".")
    // Steward's own texts are listed by the page they are on, in place of the bundle's top section.
    const topic = page === undefined ? (entry.section[0] ?? humanise(segments[0])) : t("steward.shell.page", { page })
    let parent = segments.length > 1 ? top(topic) : root
    for (let at = 1; at < segments.length - 1; at++) {
      const id = `${group}:${page === undefined ? "" : `${page}:`}${segments.slice(0, at + 1).join(".")}`
      builder.branch(id, entry.section[at] ?? humanise(segments[at]), parent)
      parent = id
    }
    const id = textId(entry)
    builder.add(parent, { kind: "leaf", id, ids: [id], value: card, wide: true })
  }
  return builder.build()
}

/** Steward's pages in their order, then every text that is on none. */
function pageRank(page: string | undefined): number {
  return page === undefined ? PAGE_GROUPS.length : PAGE_GROUPS.indexOf(page)
}

/**
 * Returns the name a message is shown by: its spec's `@Name`, else its last key segment made readable.
 */
export function messageName(entry: MessageEntry): string {
  return entry.name ?? humanise(entry.key.slice(entry.key.lastIndexOf(".") + 1))
}

export function messageLeafMatches(entry: MessageEntry, query: string): boolean {
  const needle = query.trim().toLowerCase()
  if (!needle) return true
  return [messageName(entry), entry.key, ...Object.values(entry.texts).flat(), ...Object.values(entry.overrides).flat()]
    .join("\n")
    .toLowerCase()
    .includes(needle)
}

/** Returns how many keys a tree draws, which decides whether it starts open. */
export function leafCount<L>(nodes: TreeNode<L>[]): number {
  return nodes.reduce((sum, node) => sum + (node.kind === "leaf" ? node.ids.length : leafCount(node.children)), 0)
}

export function idsBelow<L>(node: TreeNode<L>): string[] {
  return node.kind === "leaf" ? node.ids : node.children.flatMap(idsBelow)
}

/**
 * Returns the branches that must be open for the leaf that draws `id` to show, outermost first.
 */
export function ancestorsOf<L>(nodes: TreeNode<L>[], id: string): string[] | null {
  for (const node of nodes) {
    if (node.kind === "leaf") {
      if (node.ids.includes(id)) return []
      continue
    }
    const below = ancestorsOf(node.children, id)
    if (below) return [node.id, ...below]
  }
  return null
}

export function filterTree<L>(nodes: TreeNode<L>[], keep: (leaf: TreeLeaf<L>) => boolean): TreeNode<L>[] {
  const kept: TreeNode<L>[] = []
  for (const node of nodes) {
    if (node.kind === "leaf") {
      if (keep(node)) kept.push(node)
      continue
    }
    const children = filterTree(node.children, keep)
    if (children.length > 0) kept.push({ ...node, children })
  }
  return kept
}
