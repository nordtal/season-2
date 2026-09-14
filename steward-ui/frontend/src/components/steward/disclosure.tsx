import type { ReactNode } from "react"
import { ChevronRight } from "lucide-react"

/**
 * Prose that has to be somewhere but must not be in the way.
 *
 * Three pages of this interface explained themselves in cards: `/operations/restore` had two of
 * them, four hundred words, around one select and one line of shell - so on a phone you scrolled
 * past two screens of reading to reach the thing you came for, and on a desktop the eye had to find
 * the control among the paragraphs. None of that writing is wrong, and deleting it would be the
 * easy mistake: what a restore does to a volume is exactly what somebody needs to read *once*.
 *
 * So it is a `<details>`. Closed by default, one line high, and the browser gives the keyboard and
 * the screen reader their behaviour for free - which is the whole reason this is the native element
 * and not a Radix collapsible with a state to manage.
 */
export function Disclosure({
  summary,
  children,
  className,
}: {
  summary: string
  children: ReactNode
  className?: string
}) {
  return (
    <details
      className={`group rounded-md border border-border bg-card px-4 py-3 ${className ?? ""}`}
    >
      <summary className="flex cursor-pointer list-none items-center gap-2 text-sm font-medium marker:content-none focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none">
        <ChevronRight
          aria-hidden
          className="size-4 shrink-0 text-muted-foreground transition-transform duration-150 ease-out group-open:rotate-90"
        />
        {summary}
      </summary>
      <div className="flex flex-col gap-2 pt-3 pl-6 text-sm text-muted-foreground">{children}</div>
    </details>
  )
}
