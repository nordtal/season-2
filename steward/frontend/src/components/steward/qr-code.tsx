import type { QrCode as Qr } from "@/lib/api"

/**
 * A QR code the server drew as one SVG path, black on white in either theme with the four-module quiet zone a
 * scanner needs around it.
 */
export function QrCode({ qr, label }: { qr: Qr; label: string }) {
  return (
    <svg
      role="img"
      aria-label={label}
      viewBox={`-4 -4 ${qr.size + 8} ${qr.size + 8}`}
      className="size-48 rounded-md bg-white"
      shapeRendering="crispEdges"
    >
      <path d={qr.path} fill="#000" />
    </svg>
  )
}
