/**
 * A placeholder mark. The real logo is a paintbrush job and nobody's agent can do it - this is a
 * rounded square in the brand blue so the header is not empty, and it is the one place in the
 * interface where blue appears without being clickable: it is identity, not a surface.
 */
export function StewardMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 16 16" aria-hidden className={className}>
      <rect x="0.5" y="0.5" width="15" height="15" rx="4" fill="var(--primary)" />
      <path
        d="M4.6 11.4V4.6l6.8 6.8V4.6"
        fill="none"
        stroke="var(--primary-foreground)"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}
