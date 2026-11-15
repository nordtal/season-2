import type { ReactNode } from "react"

const CODE_SPAN = /`([^`\n]+)`/g

/**
 * A bundled text with each `code` span in the mono face and without its backticks, as Discord draws the same text.
 */
export function MessageText({ text }: { text: string }): ReactNode {
  const pieces: ReactNode[] = []
  let from = 0
  for (const match of text.matchAll(CODE_SPAN)) {
    pieces.push(text.slice(from, match.index))
    pieces.push(
      <code key={match.index} className="font-mono">
        {match[1]}
      </code>,
    )
    from = match.index + match[0].length
  }
  if (pieces.length === 0) return text
  pieces.push(text.slice(from))
  return pieces
}
