import { TERM_ICON } from "@/components/steward/term-icons"

import type { Npc } from "@/lib/api"
import { type Problem, type Warning, stepNumbers, stepOf } from "@/lib/npc"
import { t } from "@/lib/texts"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { ScrollArea } from "@/components/ui/scroll-area"

/**
 * The problems and warnings badge beside Publish: opens a popover listing blocking problems (English first)
 * and non-blocking warnings, each a button that navigates to and focuses the offending field.
 */

export type IssueTarget = { kind: "problem"; item: Problem } | { kind: "warning"; item: Warning }

function problemLabel(npc: Npc, numbers: Map<string, number>, item: Problem): string {
  if (item.problem === "name") return t("steward.npcs.problem-name", { language: item.language })
  if (item.problem === "place") return t("steward.npcs.problem-place")
  const number = numbers.get(item.step) ?? 1
  const step = stepOf(npc.dialogue, item.step)
  const multiPage = (step?.pages.length ?? 1) > 1
  if (item.problem === "step-text") {
    return multiPage
      ? t("steward.npcs.problem-step-page-text", { number, page: item.page + 1, language: item.language })
      : t("steward.npcs.problem-step-text", { number, language: item.language })
  }
  if (item.problem === "step-tall") {
    return multiPage
      ? t("steward.npcs.problem-step-page-tall", {
          number,
          page: item.page + 1,
          language: item.language,
          limit: item.limit,
        })
      : t("steward.npcs.problem-step-tall", { number, language: item.language, limit: item.limit })
  }
  if (item.problem === "answer-text") {
    return t("steward.npcs.problem-answer-text", { number, index: item.answer + 1, language: item.language })
  }
  return t("steward.npcs.problem-menu-unknown", { number, menu: item.menu })
}

function warningLabel(numbers: Map<string, number>, item: Warning): string {
  const number = numbers.get(item.step) ?? 1
  if (item.warning === "unlinked") return t("steward.npcs.warning-unlinked", { number })
  if (item.warning === "step-wide") {
    return t("steward.npcs.warning-step-wide", { number, language: item.language })
  }
  return t("steward.npcs.warning-answer-wide", { number, index: item.answer + 1, language: item.language })
}

export function ProblemsPopover({
  npc,
  problems,
  warnings,
  open,
  onOpenChange,
  onSelect,
}: {
  npc: Npc
  problems: Problem[]
  warnings: Warning[]
  open: boolean
  onOpenChange: (open: boolean) => void
  onSelect: (target: IssueTarget) => void
}) {
  if (problems.length === 0 && warnings.length === 0) return null
  const numbers = stepNumbers(npc.dialogue)
  const hasBlocking = problems.length > 0
  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="outline"
          size="sm"
          className={cn(
            "gap-1.5 tnum",
            hasBlocking
              ? "border-destructive/50 text-destructive hover:bg-destructive/10 hover:text-destructive"
              : "border-warning/50 text-warning hover:bg-warning/10 hover:text-warning",
          )}
        >
          <TERM_ICON.warning aria-hidden className="size-3.5 shrink-0" />
          {hasBlocking
            ? t("steward.npcs.problems", { count: problems.length })
            : t("steward.npcs.warnings", { count: warnings.length })}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-80 p-0">
        <ScrollArea className="max-h-list">
          <div className="flex flex-col gap-3 p-3">
            {problems.length > 0 ? (
              <section className="flex flex-col gap-1">
                <h3 className="px-2 text-xs font-medium font-heading text-destructive">
                  {t("steward.npcs.problems-heading")}
                </h3>
                <ul className="flex flex-col gap-0.5">
                  {problems.map((item, index) => (
                    <li key={index}>
                      <button
                        type="button"
                        className="flex w-full items-start gap-2 rounded-md px-2 py-1.5 text-left text-sm outline-none hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring"
                        onClick={() => {
                          onOpenChange(false)
                          onSelect({ kind: "problem", item })
                        }}
                      >
                        <span className="mt-1.5 size-1.5 shrink-0 rounded-full bg-destructive" aria-hidden />
                        <span className="min-w-0 flex-1">{problemLabel(npc, numbers, item)}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              </section>
            ) : null}
            {warnings.length > 0 ? (
              <section className="flex flex-col gap-1">
                <h3 className="px-2 text-xs font-medium font-heading text-warning">
                  {t("steward.npcs.warnings-heading")}
                </h3>
                <ul className="flex flex-col gap-0.5">
                  {warnings.map((item, index) => (
                    <li key={index}>
                      <button
                        type="button"
                        className="flex w-full items-start gap-2 rounded-md px-2 py-1.5 text-left text-sm outline-none hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring"
                        onClick={() => {
                          onOpenChange(false)
                          onSelect({ kind: "warning", item })
                        }}
                      >
                        <span className="mt-1.5 size-1.5 shrink-0 rounded-full bg-warning" aria-hidden />
                        <span className="min-w-0 flex-1">{warningLabel(numbers, item)}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              </section>
            ) : null}
          </div>
        </ScrollArea>
      </PopoverContent>
    </Popover>
  )
}
