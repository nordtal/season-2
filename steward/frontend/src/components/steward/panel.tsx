import type { ReactNode } from "react"
import { cn } from "cn"

/**
 * A named section without a `Card`'s border, shadow, corners and header grid.
 *
 * It never grows a border or shadow; a section that needs one is a `Card`.
 */
export function Panel({ title, children, className }: { title: string; children: ReactNode; className?: string }) {
  return (
    <section className={cn("flex flex-col gap-3", className)}>
      <h2 className="text-xs font-medium text-muted-foreground">{title}</h2>
      {children}
    </section>
  )
}
