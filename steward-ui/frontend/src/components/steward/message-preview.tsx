import { useMemo } from "react"
import { cn } from "cn"

import type { MessageArg } from "@/lib/api"
import { previewSegments } from "@/lib/mini-message"

/**
 * A text as it will read, on one line: its colours and decorations drawn, its tags gone, and each
 * placeholder a thin pill with its name. White is drawn in the page's own foreground, so a preview
 * stays readable on a light page, where Minecraft's white would vanish.
 */
export function MessagePreview({ text, args, className }: { text: string; args: MessageArg[]; className?: string }) {
  const segments = useMemo(() => previewSegments(text, args), [text, args])
  return (
    <span className={cn("block truncate", className)}>
      {segments.map((segment, index) =>
        segment.kind === "placeholder" ? (
          <span key={index} className="mx-px rounded-sm bg-muted px-1 font-mono text-[0.9em] text-muted-foreground">
            {segment.name}
          </span>
        ) : (
          <span
            key={index}
            style={{ color: segment.colour && segment.colour.toUpperCase() !== "#FFFFFF" ? segment.colour : undefined }}
            className={cn(
              segment.colour?.toUpperCase() === "#FFFFFF" && "text-foreground",
              segment.bold && "font-semibold",
              segment.italic && "italic",
              segment.underlined && "underline",
              segment.strikethrough && "line-through",
            )}
          >
            {segment.text}
          </span>
        ),
      )}
    </span>
  )
}
