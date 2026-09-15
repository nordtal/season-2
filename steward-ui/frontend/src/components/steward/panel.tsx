import type { ReactNode } from "react"
import { cn } from "cn"

/**
 * A section with a name, not a `Card`.
 *
 * `Card` costs six things every section on this page used to pay for regardless of how much it had
 * to say: a border, a shadow, rounded corners, and a `CardHeader` that is its own two-row grid. That
 * is worth it for a table of ten services; it is not worth it for three dates or a list of eight
 * journal lines, and steward/64 asked for exactly those two - Season and Latest actions - to stop
 * paying it. What is left once the frame goes is a small heading and the content, with nothing
 * between them that is not itself information.
 *
 * This is deliberately not a second card component with fewer props: a `Panel` never gets a border
 * or a shadow, because the day it grows one is the day it was a `Card` that took the scenic route.
 */
export function Panel({
  title,
  children,
  className,
}: {
  title: string
  children: ReactNode
  className?: string
}) {
  return (
    <section className={cn("flex flex-col gap-3", className)}>
      <h2 className="text-xs font-medium tracking-wide text-muted-foreground uppercase">{title}</h2>
      {children}
    </section>
  )
}
