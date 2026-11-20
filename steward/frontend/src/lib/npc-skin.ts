/** What the editor reads from a skin's PNG before it uploads it: its size, and the arms it was drawn for. */
export type SkinFile = { width: number; height: number; slim: boolean }

/** The side of a skin, in pixels. */
export const SKIN_SIDE = 64

/**
 * Whether a skin's pixels are a slim one's. A slim skin leaves the fourth column of its right arm empty, which a
 * wide-armed skin paints.
 */
export function isSlim(alphaAt: (x: number, y: number) => number): boolean {
  for (let y = 20; y < 32; y++) {
    if (alphaAt(54, y) !== 0) return false
  }
  return true
}

/** Reads an image file's size and arms. It rejects a file the browser cannot draw. */
export async function readSkin(file: Blob): Promise<SkinFile> {
  const image = await createImageBitmap(file)
  try {
    const { width, height } = image
    if (width !== SKIN_SIDE || height !== SKIN_SIDE) return { width, height, slim: false }
    const canvas = document.createElement("canvas")
    canvas.width = width
    canvas.height = height
    const context = canvas.getContext("2d")
    if (context === null) return { width, height, slim: false }
    context.drawImage(image, 0, 0)
    const pixels = context.getImageData(0, 0, width, height).data
    return { width, height, slim: isSlim((x, y) => pixels[(y * width + x) * 4 + 3]) }
  } finally {
    image.close()
  }
}

/** What Steward takes as a portrait picture: a square PNG this wide, at most this many bytes. */
export const ARTWORK = { smallest: 40, largest: 1024, bytes: 1024 * 1024 }

/** Reads an image file's size. It rejects a file the browser cannot draw. */
export async function readPicture(file: Blob): Promise<{ width: number; height: number }> {
  const image = await createImageBitmap(file)
  try {
    return { width: image.width, height: image.height }
  } finally {
    image.close()
  }
}
