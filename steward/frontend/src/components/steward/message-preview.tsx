import { useMemo } from "react"
import { cn } from "cn"

import { colourOf, formatOf, parse } from "@/lib/rich-text"
import { useGlyphs, useMessageSyntax } from "@/lib/queries"

/**
 * A text on one line: colours and decorations drawn, tags gone, values as named pills, glyphs a line high.
 *
 * White is drawn in the page's foreground; a text the parser cannot read is shown as it is written.
 */
export function MessagePreview({ text, format, className }: { text: string; format?: string; className?: string }) {
  const syntax = useMessageSyntax()
  const glyphs = useGlyphs()
  const tones = useMemo(() => syntax.data?.tones ?? {}, [syntax.data])
  const runs = useMemo(() => parse(text, formatOf(format), Object.keys(tones)), [text, format, tones])
  if (runs === null) return <span className={cn("block truncate font-mono", className)}>{text}</span>
  return (
    <span className={cn("block truncate", className)}>
      {runs.map((run, index) => {
        switch (run.kind) {
          case "placeholder":
            return (
              <span key={index} className="mx-px rounded-sm bg-muted px-1 font-mono text-[0.9em] text-muted-foreground">
                {run.name}
              </span>
            )
          case "glyph": {
            const glyph = glyphs.data?.find((candidate) => candidate.name === run.name)
            return glyph ? (
              <img
                key={index}
                src={`/glyphs/${glyph.image}`}
                alt={run.name}
                className="mx-px inline-block align-[-0.15em]"
                style={{ height: "1em", width: "auto", maxWidth: "none", imageRendering: "pixelated" }}
              />
            ) : null
          }
          case "break":
            return " "
          case "raw":
            return (
              <span key={index} className="font-mono">
                {run.source}
              </span>
            )
          default: {
            const colour = colourOf(run.style, tones, "#FFFFFF").toUpperCase()
            return (
              <span
                key={index}
                style={{ color: colour === "#FFFFFF" ? undefined : colour }}
                className={cn(
                  colour === "#FFFFFF" && "text-foreground",
                  run.style.bold && "font-semibold",
                  run.style.italic && "italic",
                  run.style.underlined && "underline",
                  run.style.strikethrough && "line-through",
                )}
              >
                {run.text}
              </span>
            )
          }
        }
      })}
    </span>
  )
}
