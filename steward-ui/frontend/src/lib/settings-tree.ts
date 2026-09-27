import type { ConfigEntry, MessageEntry } from "@/lib/api"
import { entryHaystack } from "@/lib/settings-search"
import { colourValue } from "@/components/steward/config-controls"
import { sentenceOf } from "@/lib/words"
import { colourRuns } from "@/components/steward/colour-control"
import { pairedBlocks, pairedPaths, type PairedBlocks } from "@/components/steward/paired-blocks"

/**
 * One leaf of a file's settings tree, built as data so its shape can be tested without drawing.
 *
 * Keys are never shown but stay the identity: a leaf's `ids` are what a draft, a search hit and a jump name.
 */
export type TreeLeaf<L> = {
  kind: "leaf"
  id: string
  /** Every key this leaf draws: one for a field, several for a colour run or a paired block. */
  ids: string[]
  value: L
  /** Takes the whole width of the grid rather than one cell. */
  wide: boolean
}

export type TreeBranch<L> = {
  kind: "branch"
  id: string
  /** One name, or several when a chain of single-child branches was folded into one row. */
  labels: string[]
  children: TreeNode<L>[]
}

export type TreeNode<L> = TreeLeaf<L> | TreeBranch<L>

/** `farm-world` reads "Farm world" and `serverUuid` "Server UUID", for a section nothing named. */
export function humanise(segment: string): string {
  return sentenceOf(segment.split(/[-_.\s]+/)) || segment
}

class Builder<L> {
  readonly root: TreeNode<L>[] = []
  private readonly branches = new Map<string, TreeBranch<L>>()

  /** The branch `id`, created under `parent` on first use. */
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

  /** A branch with one branch child becomes one row with both names, and a lone top section is not a root. */
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

// Config files

export type ConfigLeafValue =
  | { kind: "entry"; entry: ConfigEntry }
  /** Several colours of one section, side by side (`colourRuns`). */
  | { kind: "run"; entries: ConfigEntry[] }
  /** Two sibling sections with the same keys, one row per key (`pairedBlocks`). */
  | { kind: "pair"; pair: PairedBlocks }

/** Whether a config entry needs the whole width: a list, a list of sections, several lines. */
function wideEntry(entry: ConfigEntry): boolean {
  return entry.kind === "LIST" || entry.kind === "SECTIONS" || !entry.editable || (entry.value ?? "").includes("\n")
}

export function configTree(entries: ConfigEntry[]): TreeNode<ConfigLeafValue>[] {
  const builder = new Builder<ConfigLeafValue>()
  const pairs = pairedBlocks(entries)
  const paired = pairedPaths(pairs)
  const runs = colourRuns(
    entries.filter((entry) => entry.kind !== "MAP" && !paired.has(entry.path)),
    (entry) => colourValue(entry) !== null,
  )
  const runOf = new Map<string, ConfigEntry[]>()
  for (const run of runs) for (const member of run) runOf.set(member.path, run)
  const labelOf = new Map(entries.filter((entry) => entry.kind === "MAP").map((entry) => [entry.path, entry.label]))

  /** A section never listed as its own MAP still gets a branch, so every leaf has somewhere to hang. */
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

/** What a config leaf is found by: name, key, value and explanation of every entry it draws. */
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

// Message bundles

export function messageTree(entries: MessageEntry[]): TreeNode<MessageEntry>[] {
  const builder = new Builder<MessageEntry>()
  for (const entry of entries) {
    const segments = entry.key.split(".")
    let parent: string | null = null
    for (let at = 0; at < segments.length - 1; at++) {
      const id = segments.slice(0, at + 1).join(".")
      builder.branch(id, entry.section[at] ?? humanise(segments[at]), parent)
      parent = id
    }
    builder.add(parent, { kind: "leaf", id: entry.key, ids: [entry.key], value: entry, wide: true })
  }
  return builder.build()
}

/** The name a message is shown by: its spec's `@Name`, or its last key segment made readable. */
export function messageName(entry: MessageEntry): string {
  return entry.name ?? humanise(entry.key.slice(entry.key.lastIndexOf(".") + 1))
}

/** What a message is found by: its name, its key, and both languages, packaged and overridden. */
export function messageLeafMatches(entry: MessageEntry, query: string): boolean {
  const needle = query.trim().toLowerCase()
  if (!needle) return true
  return [messageName(entry), entry.key, entry.english, entry.german, entry.overrideEnglish, entry.overrideGerman]
    .filter((part): part is string => Boolean(part))
    .join("\n")
    .toLowerCase()
    .includes(needle)
}

// Walking a tree

/** How many keys a tree draws, which decides whether it starts open. */
export function leafCount<L>(nodes: TreeNode<L>[]): number {
  return nodes.reduce((sum, node) => sum + (node.kind === "leaf" ? node.ids.length : leafCount(node.children)), 0)
}

/** The keys below a node, for a branch's count of changed ones. */
export function idsBelow<L>(node: TreeNode<L>): string[] {
  return node.kind === "leaf" ? node.ids : node.children.flatMap(idsBelow)
}

/** The branches that have to be open for the leaf drawing `id` to be seen, outermost first. */
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

/** The tree with only the leaves `keep` accepts, and only the branches still holding one. */
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
