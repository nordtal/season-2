import type { CommandNode } from "@/lib/api"

/**
 * What the console offers for the word being typed, read from the tree that the server published of itself.
 *
 * Every word before it is walked from the root. A word matches a word of the tree, and an argument takes any one word.
 */
export type Suggestions = {
  /** Where the word being typed starts. A completion replaces the line from here. */
  from: number
  /** The words that complete it, and whether the command goes on after one. */
  words: Word[]
  /** The arguments expected here, by their names. They cannot be completed, only typed. */
  arguments: string[]
}

export type Word = { name: string; more: boolean }

/**
 * No one scrolls through more words than this. The tree lists the plain words before the namespaced ones.
 */
const SHOWN = 50

export function suggest(nodes: readonly CommandNode[], line: string): Suggestions {
  const from = line.lastIndexOf(" ") + 1
  let at = new Set<number>(nodes.length > 0 ? [0] : [])
  for (const typed of line.slice(0, from).split(" ").slice(0, -1)) {
    const next = new Set<number>()
    for (const index of at) {
      for (const child of childrenOf(nodes, index)) {
        if (nodes[child].argument || same(nodes[child].name, typed)) next.add(child)
      }
    }
    at = next
  }

  const partial = line.slice(from).toLowerCase()
  const words = new Map<string, boolean>()
  const expected = new Set<string>()
  for (const index of at) {
    for (const child of childrenOf(nodes, index)) {
      const node = nodes[child]
      if (node.argument) expected.add(node.name)
      else if (node.name.toLowerCase().startsWith(partial) && !words.has(node.name)) {
        words.set(node.name, (node.children?.length ?? 0) > 0 || node.redirect !== undefined)
      }
    }
  }
  return {
    from,
    words: [...words].slice(0, SHOWN).map(([name, more]) => ({ name, more })),
    arguments: [...expected],
  }
}

/**
 * Returns the line with the word being typed replaced, and a space after it when the command goes on.
 */
export function complete(line: string, from: number, word: Word): string {
  return `${line.slice(0, from)}${word.name}${word.more ? " " : ""}`
}

/**
 * Returns a node's children, or those of the node it redirects to, as Brigadier continues there. Indexes outside the list are left out.
 */
function childrenOf(nodes: readonly CommandNode[], index: number): number[] {
  const node = nodes[index]
  if (!node) return []
  const target = node.redirect === undefined ? node : nodes[node.redirect]
  return (target?.children ?? []).filter((child) => child >= 0 && child < nodes.length)
}

function same(word: string, typed: string): boolean {
  return word.toLowerCase() === typed.toLowerCase()
}
