import { ArrowUpIcon, CaretRightIcon } from "@phosphor-icons/react"
import { Fragment, use, useEffect, useMemo, useRef, useState } from "react"
import { createPortal } from "react-dom"
import type { ReactNode, RefObject } from "react"
import { cn } from "cn"

import type { ConfigLocation } from "@/lib/api"
import { setOpened, useOpened } from "@/lib/drafts"
import { type Language } from "@/lib/message-text"
import {
  ancestorsOf,
  filterTree,
  idsBelow,
  leafCount,
  type TreeBranch,
  type TreeLeaf,
  type TreeNode,
} from "@/lib/settings-tree"
import { CONTROL, PhoneBarSlot } from "@/app/island"
import { Button } from "@/components/ui/button"
import { FilterBar, SearchField } from "@/components/steward/filter-bar"
import { t } from "@/lib/texts"

export type Target = { file: string; id: string; language?: Language; seq: number }

export type FileItem = {
  id: string
  label: string
  readable: boolean
  writable: boolean
  location: ConfigLocation
  editor?: string
}

export function useWide(): boolean {
  const query = "(min-width: 64rem)"
  const [wide, setWide] = useState(() => typeof window !== "undefined" && window.matchMedia?.(query).matches)
  useEffect(() => {
    const media = window.matchMedia?.(query)
    if (!media) return undefined
    const update = () => setWide(media.matches)
    update()
    media.addEventListener("change", update)
    return () => media.removeEventListener("change", update)
  }, [])
  return wide
}

export function DraftDot() {
  return <span aria-label={t("steward.settings.changed")} className="size-1.5 shrink-0 rounded-full bg-primary" />
}

export type Highlight = { id: string; seq: number; language?: Language }

export type OutsideSearch = { query: string; onQuery: (query: string) => void; at: RefObject<HTMLElement | null> }

export function TreeView<L>({
  file,
  nodes,
  draftIds,
  matches,
  notice,
  target,
  renderLeaf,
  save,
  above,
  collapsed,
  list,
  cards,
  search: outside,
  searchLabel = t("steward.settings.search-file"),
  empty = t("steward.settings.empty-file"),
}: {
  file: string
  nodes: TreeNode<L>[]
  draftIds: Set<string>
  matches: (value: L, query: string) => boolean
  notice: string | null
  target: Target | null
  renderLeaf: (leaf: TreeLeaf<L>, highlight: Highlight | null) => ReactNode
  save: ReactNode
  above?: ReactNode
  collapsed?: boolean
  list?: boolean
  /** Leaves are cards, one to a row. */
  cards?: boolean
  /** The page clears it for a jump. */
  search?: OutsideSearch
  searchLabel?: string
  empty?: string
}) {
  const [ownQuery, setOwnQuery] = useState("")
  const query = outside?.query ?? ownQuery
  const setQuery = outside?.onQuery ?? setOwnQuery
  const [highlight, setHighlight] = useState<Highlight | null>(null)
  const opened = useOpened(file)
  const ownSearch = useRef<HTMLDivElement>(null)
  const search = outside?.at ?? ownSearch
  const [appliedSeq, setAppliedSeq] = useState(-1)
  /**
   * The branches that the last jump opens. They are kept as state, because the render that finds them is redone before it commits.
   */
  const [jumpChain, setJumpChain] = useState<string[] | null>(null)
  const closedByDefault = useMemo(() => collapsed === true || leafCount(nodes) > 12, [nodes, collapsed])

  const shown = useMemo(
    () => (query.trim() ? filterTree(nodes, (leaf) => matches(leaf.value, query)) : nodes),
    [nodes, query, matches],
  )

  /**
   * Opens the chain to a new jump target during render, so it lands in one commit. `appliedSeq` marks the last target.
   */
  const chainForTarget =
    target && target.file === file && target.seq !== appliedSeq ? ancestorsOf(nodes, target.id) : null

  if (chainForTarget && target) {
    setAppliedSeq(target.seq)
    setJumpChain(chainForTarget)
    if (!outside) setOwnQuery("")
    setHighlight({ id: target.id, seq: target.seq, language: target.language })
  }

  /**
   * Opening a branch writes to the shared draft store, an external system, so this part stays an effect.
   */
  useEffect(() => {
    if (!jumpChain) return
    for (const branch of jumpChain) setOpened(file, branch, true)
  }, [jumpChain, file])

  useEffect(() => {
    if (!highlight) return undefined
    const timeout = window.setTimeout(() => setHighlight(null), 2400)
    return () => window.clearTimeout(timeout)
  }, [highlight])

  const isOpen = (id: string) => (query.trim() ? true : (opened[id] ?? !closedByDefault))

  return (
    <div className="flex flex-col gap-3">
      {outside ? null : (
        <FilterBar ref={ownSearch}>
          <SearchField value={query} onValueChange={setQuery} label={searchLabel} />
        </FilterBar>
      )}
      {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
      {above}
      {shown.length === 0 ? (
        <p className="text-sm text-muted-foreground">{query.trim() ? t("steward.settings.no-match") : empty}</p>
      ) : (
        <NodeList
          nodes={shown}
          isOpen={isOpen}
          onToggle={(id) => setOpened(file, id, !isOpen(id))}
          draftIds={draftIds}
          highlight={highlight}
          renderLeaf={renderLeaf}
          list={list}
          cards={cards}
        />
      )}
      <PinnedRow>
        <BackToTop at={search} />
        {save}
      </PinnedRow>
    </div>
  )
}

/** Shows once `at` has scrolled out of view. On a phone it sits in the navigation bar, where it covers nothing of the page. */
export function BackToTop({ at }: { at: RefObject<HTMLElement | null> }) {
  const [scrolledAway, setScrolledAway] = useState(false)
  const phoneBar = use(PhoneBarSlot)
  useEffect(() => {
    const node = at.current
    if (!node || typeof IntersectionObserver === "undefined") return undefined
    const observer = new IntersectionObserver(([entry]) => setScrolledAway(!entry.isIntersecting))
    observer.observe(node)
    return () => observer.disconnect()
  }, [at])
  if (!scrolledAway) return null
  if (phoneBar) {
    return createPortal(
      <button
        type="button"
        aria-label={t("steward.settings.back-to-top")}
        className={CONTROL}
        onClick={() => scrollPageToTop(at.current)}
      >
        <ArrowUpIcon className="size-4" aria-hidden />
      </button>,
      phoneBar,
    )
  }
  return (
    <Button
      type="button"
      size="icon"
      aria-label={t("steward.settings.back-to-top")}
      className="pointer-events-auto rounded-full shadow-sm"
      onClick={() => scrollPageToTop(at.current)}
    >
      <ArrowUpIcon aria-hidden />
    </Button>
  )
}

/** The page scrolls inside the shell's scroll area; without one, the window scrolls. */
function scrollPageToTop(from: Element | null) {
  const page = from?.closest('[data-slot="scroll-area-viewport"]') ?? window
  page.scrollTo({ top: 0, behavior: "smooth" })
}

export function PinnedRow({ children }: { children: ReactNode }) {
  return (
    <div className="pointer-events-none sticky bottom-(--content-bottom) mt-2 flex items-center justify-end gap-2">
      {children}
    </div>
  )
}

export function NodeList<L>({
  nodes,
  isOpen,
  onToggle,
  draftIds,
  highlight,
  renderLeaf,
  branchIcon,
  list,
  cards,
  nested,
}: {
  nodes: TreeNode<L>[]
  isOpen: (id: string) => boolean
  onToggle: (id: string) => void
  draftIds: Set<string>
  highlight: Highlight | null
  renderLeaf: (leaf: TreeLeaf<L>, highlight: Highlight | null) => ReactNode
  /** Drawn before the name, for a list whose branches are things of their own; the caret then follows the name. */
  branchIcon?: (branch: TreeBranch<L>) => ReactNode
  list?: boolean
  cards?: boolean
  nested?: boolean
}) {
  const groups: Array<TreeLeaf<L>[] | TreeBranch<L>> = []
  for (const node of nodes) {
    const last = groups[groups.length - 1]
    if (node.kind === "leaf") {
      if (Array.isArray(last)) last.push(node)
      else groups.push([node])
    } else {
      groups.push(node)
    }
  }

  return (
    <div className="flex flex-col">
      {groups.map((group) => {
        if (Array.isArray(group)) {
          return (
            <div
              key={`leaves-${group[0].id}`}
              className={cn(
                cards
                  ? "flex flex-col gap-3 py-2"
                  : list
                    ? "flex flex-col"
                    : "grid grid-cols-[repeat(auto-fill,minmax(13rem,1fr))] gap-x-6 gap-y-4 py-2",
                // Under a branch, a leaf's text starts past the caret's column; the first level starts at the search field. A list's caret follows the name, so only the branch's indent applies. A card on a phone keeps that width for its editor.
                nested && !list && (cards ? "md:pl-6" : "pl-8"),
              )}
            >
              {group.map((leaf) => (
                <div key={leaf.id} className={cn("min-w-0", !list && !cards && leaf.wide && "col-span-full")}>
                  {renderLeaf(leaf, highlight && leaf.ids.includes(highlight.id) ? highlight : null)}
                </div>
              ))}
            </div>
          )
        }
        const open = isOpen(group.id)
        const count = idsBelow(group).filter((id) => draftIds.has(id)).length
        const caret = (
          <CaretRightIcon
            aria-hidden
            data-caret
            className={cn("size-4 shrink-0 text-muted-foreground transition-transform", open && "rotate-90")}
          />
        )
        return (
          <Fragment key={group.id}>
            <button
              type="button"
              aria-expanded={open}
              onClick={() => onToggle(group.id)}
              className="flex h-9 w-full items-center gap-2 rounded-md px-2 text-left text-sm font-medium hover:bg-accent/50"
            >
              {branchIcon ? null : caret}
              {branchIcon?.(group)}
              <span className="flex min-w-0 items-center gap-1 truncate">
                {group.labels.map((label, index) => (
                  <Fragment key={index}>
                    {index > 0 ? (
                      <CaretRightIcon aria-hidden className="size-3 shrink-0 text-muted-foreground" />
                    ) : null}
                    <span className="truncate">{label}</span>
                  </Fragment>
                ))}
              </span>
              {/* A row with an icon keeps the icon column of the file rows, so its caret follows the name. */}
              {branchIcon ? caret : null}
              {count > 0 ? (
                <span className="ml-auto flex shrink-0 items-center gap-1.5 text-xs text-muted-foreground tabular-nums">
                  <DraftDot />
                  {count}
                </span>
              ) : null}
            </button>
            {open ? (
              <div className="pl-4">
                <NodeList
                  nodes={group.children}
                  isOpen={isOpen}
                  onToggle={onToggle}
                  draftIds={draftIds}
                  highlight={highlight}
                  renderLeaf={renderLeaf}
                  branchIcon={branchIcon}
                  list={list}
                  cards={cards}
                  nested
                />
              </div>
            ) : null}
          </Fragment>
        )
      })}
    </div>
  )
}

export function useLanding(highlight: Highlight | null) {
  const ref = useRef<HTMLDivElement>(null)
  const seq = highlight?.seq
  useEffect(() => {
    if (seq === undefined) return
    ref.current?.scrollIntoView({ behavior: "smooth", block: "center" })
  }, [seq])
  return ref
}

export const LIT = "bg-accent ring-2 ring-primary ring-offset-4 ring-offset-background"

/** A sequence number that no highlight has, so the first render always counts as new. */
export const UNSEEN = Symbol("unseen")
