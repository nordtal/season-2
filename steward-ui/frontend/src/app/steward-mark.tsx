/**
 * The mark: the server icon, and deliberately the same file.
 *
 * `resource-pack/src/pack.png` is what a player sees beside the MOTD in the server browser and what
 * the resource pack carries as its own picture. It is the favicon, the home-screen icon and this -
 * so the thing in a phone's app switcher is the thing in the server list, and there is one mark to
 * keep rather than three that drift. `public/icon.png` is a byte-for-byte copy of it, held there by
 * `MarkIsTheServerIconTest`, because a frontend cannot read across the repository at build time.
 *
 * It is drawn at 20 to 32 pixels, which is a *downscale* of 128 - so the browser's smooth resampler
 * is the right one and there is no `image-rendering` here. The upscale is the other case, and the
 * one that needs nearest neighbour; that is `icon-512.png` and it is generated, not scaled by CSS.
 */
export function StewardMark({ className }: { className?: string }) {
  return (
    <img
      src="/icon.png"
      alt=""
      aria-hidden
      width={128}
      height={128}
      className={className}
      // Square, and the interface's own radius - the source is a square picture with no rounding
      // of its own, and an unrounded square beside rounded cards reads as a sticker.
      style={{ borderRadius: "var(--radius-sm)" }}
    />
  )
}
