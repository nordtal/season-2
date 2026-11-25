import { ArrowCounterClockwiseIcon, PlusIcon } from "@/components/ui/icons"
import type { ReactNode } from "react"

import type { Dialogue, NpcMenu } from "@/lib/api"
import { type Language, LANGUAGES, missingCount, ordered, stepNumbers } from "@/lib/npc"
import { t } from "@/lib/texts"
import { cn } from "@/lib/utils"
import type { TooLong } from "@/components/steward/npc/graph"
import { StepBadge } from "@/components/steward/npc/step-node"
import { menuName } from "@/components/steward/npc/step-panel"
import { Hint } from "@/components/steward/status"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"

/** Indentation classes by dialogue depth for the narrow-screen list, capped so narrow viewports keep readable cards. */
const DEPTH_INDENT = ["pl-0", "pl-4", "pl-8", "pl-12", "pl-16"] as const

/** The steps as an indented list for narrow viewports (`< xl`), opening the inspector in a ResponsiveDialog on tap. */
export function StepList({
  dialogue,
  menus,
  selected,
  tooLong,
  language,
  onSelect,
  className,
}: {
  dialogue: Dialogue
  menus: NpcMenu[]
  selected: string | null
  tooLong: TooLong
  language: Language
  onSelect: (id: string) => void
  className?: string
}) {
  const entries = ordered(dialogue)
  const numbers = stepNumbers(dialogue)
  return (
    <ol aria-label={t("steward.npcs.graph")} className={cn("flex min-w-0 flex-col gap-2.5", className)}>
      {entries.map(({ step, linked, depth }) => {
        const number = numbers.get(step.id) ?? 1
        const chosen = selected === step.id
        const body = step.pages[0]?.[language]?.trim() ?? ""
        const missing = missingCount(step)
        const indent = DEPTH_INDENT[Math.min(depth, DEPTH_INDENT.length - 1)]
        const thenLabel =
          step.menu !== undefined
            ? menuName(menus, step.menu)
            : step.next === undefined
              ? t("steward.npcs.end")
              : t("steward.npcs.step-number", { number: numbers.get(step.next) ?? 1 })
        return (
          <li key={step.id} className={cn("min-w-0", indent)}>
            <button
              type="button"
              data-step={step.id}
              aria-pressed={chosen}
              onClick={() => onSelect(step.id)}
              className={cn(
                "flex w-full min-w-0 flex-col gap-2 rounded-lg border bg-card p-3 text-left outline-none focus-visible:ring-2 focus-visible:ring-ring",
                chosen && "border-primary ring-1 ring-primary",
              )}
            >
              <div className="flex min-w-0 items-center gap-1.5">
                <span className="shrink-0 text-xs font-medium font-heading text-muted-foreground tnum">
                  {t("steward.npcs.step-number", { number })}
                </span>
                {dialogue.start === step.id ? (
                  <Badge variant="secondary" className="gap-1 px-1.5 text-badge">
                    <TERM_ICON.start aria-hidden className="size-3 text-primary" />
                    {t("steward.npcs.start")}
                  </Badge>
                ) : null}
                {step.pages.length > 1 ? (
                  <Badge variant="outline" className="px-1.5 text-badge tnum">
                    {t("steward.npcs.page-count", { count: step.pages.length })}
                  </Badge>
                ) : null}
                <span className="flex min-w-0 flex-1 justify-end">
                  <StepBadge missing={missing} tooLong={tooLong.has(step.id)} linked={linked} />
                </span>
              </div>
              <p
                lang={language}
                className={cn("line-clamp-3 text-sm leading-snug", body === "" && "text-muted-foreground")}
              >
                {body || t("steward.npcs.no-text")}
              </p>
              {step.answers.length > 0 ? (
                <div className="flex flex-col gap-1 border-t border-border/60 pt-2">
                  {step.answers.map((answer, index) => {
                    const text = answer.text[language].trim()
                    const nextNum = answer.next ? numbers.get(answer.next) : undefined
                    return (
                      <div key={index} className="flex items-center justify-between gap-2 text-xs">
                        <span
                          lang={language}
                          className={cn("min-w-0 truncate", text === "" && "text-muted-foreground")}
                        >
                          {text || t("steward.npcs.no-text")}
                        </span>
                        <span className="shrink-0 text-muted-foreground tnum">
                          {nextNum !== undefined
                            ? t("steward.npcs.step-number", { number: nextNum })
                            : t("steward.npcs.end")}
                        </span>
                      </div>
                    )
                  })}
                </div>
              ) : (
                <div className="flex items-center justify-between border-t border-border/60 pt-2 text-xs text-muted-foreground">
                  <span>{thenLabel}</span>
                </div>
              )}
            </button>
          </li>
        )
      })}
    </ol>
  )
}

/** Narrow-screen dialogue view below `xl`: toolbar, depth-indented step list, and inspector dialog. */
export function NarrowDialogueView({
  dialogue,
  menus,
  selected,
  selectedNumber,
  tooLong,
  language,
  canUndo,
  canRedo,
  inspectorOpen,
  inspectorBody,
  onSelect,
  onInspectorOpenChange,
  onAddStep,
  onUndo,
  onRedo,
  onLanguage,
}: {
  dialogue: Dialogue
  menus: NpcMenu[]
  selected: string | null
  selectedNumber: number
  tooLong: TooLong
  language: Language
  canUndo: boolean
  canRedo: boolean
  inspectorOpen: boolean
  inspectorBody: ReactNode
  onSelect: (id: string) => void
  onInspectorOpenChange: (open: boolean) => void
  onAddStep: () => void
  onUndo: () => void
  onRedo: () => void
  onLanguage: (language: Language) => void
}) {
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" variant="outline" size="sm" onClick={onAddStep}>
          <PlusIcon aria-hidden />
          {t("steward.npcs.add-step")}
        </Button>
        <Hint tip={t("steward.npcs.undo")}>
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            disabled={!canUndo}
            aria-label={t("steward.npcs.undo")}
            onClick={onUndo}
          >
            <ArrowCounterClockwiseIcon aria-hidden />
          </Button>
        </Hint>
        <Hint tip={t("steward.npcs.redo")}>
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            disabled={!canRedo}
            aria-label={t("steward.npcs.redo")}
            onClick={onRedo}
          >
            <ArrowCounterClockwiseIcon aria-hidden className="-scale-x-100" />
          </Button>
        </Hint>
        <div
          role="group"
          aria-label={t("steward.npcs.canvas-language")}
          className="ml-auto flex items-center rounded-md bg-muted p-0.5"
        >
          {LANGUAGES.map((code) => (
            <button
              key={code}
              type="button"
              aria-pressed={language === code}
              className={cn(
                "rounded-sm px-2 py-1 text-xs font-medium outline-none focus-visible:ring-2 focus-visible:ring-ring",
                language === code ? "bg-card text-foreground shadow-xs" : "text-muted-foreground hover:text-foreground",
              )}
              onClick={() => onLanguage(code)}
            >
              {t("steward.npcs.language", { language: code })}
            </button>
          ))}
        </div>
      </div>
      <StepList
        dialogue={dialogue}
        menus={menus}
        selected={selected}
        tooLong={tooLong}
        language={language}
        onSelect={onSelect}
      />
      <ResponsiveDialog open={inspectorOpen && selected !== null} onOpenChange={onInspectorOpenChange}>
        <ResponsiveDialogContent
          className="flex h-[calc(var(--app-height)*0.85)] max-h-[calc(var(--app-height)*0.85)] flex-col overflow-hidden p-0 pt-6 sm:max-w-lg"
          aria-describedby={undefined}
        >
          <ResponsiveDialogHeader className="sr-only">
            <ResponsiveDialogTitle>{t("steward.npcs.step-number", { number: selectedNumber })}</ResponsiveDialogTitle>
          </ResponsiveDialogHeader>
          {inspectorBody}
        </ResponsiveDialogContent>
      </ResponsiveDialog>
    </div>
  )
}
