import { SERVICE_ICON, TERM_ICON, type Glyph } from "@/components/steward/term-icons"
import { standbyOf } from "@/lib/services"
import { cn } from "@/lib/utils"
import { serviceTitle } from "@/lib/words"

/** A standby takes the icon of the service it stands in for, marked with the term `standby` in its corner. */
export function ServiceIcon({ service, className }: { service: string; className?: string }) {
  const primary = standbyOf(service)
  const Drawn = SERVICE_ICON[primary ?? service] ?? TERM_ICON.service
  if (primary === undefined) return <Drawn aria-hidden className={className} />
  return (
    <span aria-hidden data-standby className={cn("relative inline-flex", className)}>
      <Drawn className="size-full" />
      <TERM_ICON.standby weight="fill" className="absolute -right-0.5 -bottom-0.5 size-1/2 text-foreground" />
    </span>
  )
}

const GLYPHS = new Map<string, Glyph>()

/** Returns one service's icon as a component, the same one on every call, for a place that takes an icon. */
export function serviceGlyph(service: string): Glyph {
  let glyph = GLYPHS.get(service)
  if (glyph === undefined) {
    glyph = function Glyph({ className }: { className?: string }) {
      return <ServiceIcon service={service} className={className} />
    }
    GLYPHS.set(service, glyph)
  }
  return glyph
}

/** A service as a person reads it: its icon and its name. */
export function ServiceName({
  service,
  className,
  iconClassName,
}: {
  service: string
  className?: string
  iconClassName?: string
}) {
  return (
    <span className={cn("inline-flex min-w-0 items-center gap-1.5", className)}>
      <ServiceIcon service={service} className={iconClassName ?? "size-4 shrink-0 text-muted-foreground"} />
      <span className="truncate">{serviceTitle(service)}</span>
    </span>
  )
}
