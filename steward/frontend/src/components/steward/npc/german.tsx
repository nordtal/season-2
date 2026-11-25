import { createContext, useContext, type ReactNode } from "react"

import { t } from "@/lib/texts"
import { StatusBadge } from "@/components/steward/status"
import { Button } from "@/components/ui/button"
import { CaretDownIcon, CaretRightIcon } from "@/components/ui/icons"

/**
 * English comes first for every NPC text, and German opens under it. Which German sections are open is the editor's,
 * so a refused publish can open the one that lacks its text.
 */

export type GermanSections = { open: ReadonlySet<string>; toggle: (key: string, open: boolean) => void }

const Sections = createContext<GermanSections>({ open: new Set(), toggle: () => {} })

export const GermanProvider = Sections.Provider

/** The key of the German section of a step's text, of an answer's or of the name. */
export const germanKey = {
  name: "name",
  step: (step: string, page = 0) => `step:${step}:${page}`,
  answer: (step: string, index: number) => `answer:${step}:${index}`,
}

/** A German text under its English one: closed, it says whether the text is missing or too long for its room. */
export function German({
  id,
  missing,
  long = false,
  children,
}: {
  id: string
  missing: boolean
  long?: boolean
  children: ReactNode
}) {
  const { open, toggle } = useContext(Sections)
  const shown = open.has(id)
  const Caret = shown ? CaretDownIcon : CaretRightIcon
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex flex-wrap items-center gap-2">
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="-ml-2 text-muted-foreground"
          aria-expanded={shown}
          onClick={() => toggle(id, !shown)}
        >
          <Caret aria-hidden />
          {t("steward.npcs.language", { language: "de" })}
        </Button>
        {missing && !shown ? (
          <StatusBadge tone="down" className="px-1.5 text-badge">
            {t("steward.npcs.missing", { language: "de" })}
          </StatusBadge>
        ) : long && !shown ? (
          <StatusBadge tone="warn" className="px-1.5 text-badge">
            {t("steward.npcs.too-long", { language: "de" })}
          </StatusBadge>
        ) : null}
      </div>
      {shown ? children : null}
    </div>
  )
}
