/**
 * The mark. It is the `pack.png` that players see as the server icon, and it is also the favicon and the home screen icon.
 *
 * `public/icon.png` is a copy that `MarkIsTheServerIconTest` holds. The image is only scaled down, so it sets no `image-rendering`.
 */
export function StewardMark({
  className,
  round = false,
}: {
  className?: string
  /** Draws the mark as a circle, for the bot's Discord avatar. */
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
      /** Uses the interface's own radius, so the square matches the rounded cards beside it. */
      style={{ borderRadius: round ? "50%" : "var(--radius-sm)" }}
    />
  )
}
