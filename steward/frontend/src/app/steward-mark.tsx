/**
 * The `pack.png` that players see as the server icon. `public/icon.png` is a copy that `MarkIsTheServerIconTest` holds. The image is only scaled down, so it sets no `image-rendering`.
 */
export function StewardMark({ className, round = false }: { className?: string; round?: boolean }) {
  return (
    <img
      src="/icon.png"
      alt=""
      aria-hidden
      width={128}
      height={128}
      className={className}
      style={{ borderRadius: round ? "50%" : "var(--radius-sm)" }}
    />
  )
}
