import { dateTime, relative } from "@/lib/format"
import { Tip } from "@/components/steward/status"

/** An instant as a span from now, its date and time on a tap or a hover. */
export function When({ at, now }: { at: string; now?: number }) {
  return (
    <Tip content={dateTime(at)}>
      <time dateTime={at}>{relative(at, now)}</time>
    </Tip>
  )
}
