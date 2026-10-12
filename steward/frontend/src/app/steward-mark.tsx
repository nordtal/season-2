/**
 * The mark, the same `pack.png` a player sees as the server icon, used as favicon and home screen icon too.
 *
 * `public/icon.png` is a copy held by `MarkIsTheServerIconTest`. It is only ever downscaled, so no `image-rendering`.
 */
export function StewardMark({
  className,
  round = false,
}: {
  className?: string
  /** Drawn as a circle, where it stands for the bot as a Discord avatar. */
  round?: boolean
}) {
  return (
    <img
      src="/icon.png"
      alt=""
      aria-hidden
      width={128}
      height={128}
      className={className}
      /** The interface's own radius, since an unrounded square beside rounded cards reads as a sticker. */
      style={{ borderRadius: round ? "50%" : "var(--radius-sm)" }}
    />
  )
}
