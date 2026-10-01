/** The version pair behind two filenames, or the filenames themselves when they do not come apart. */
export type Jump = {
  /** What is installed: a version when the pair came apart, the filename when it did not. */
  from: string
  /** What would be installed: the publisher's version, or the filename as a last resort. */
  to: string
  /** Whether `from` is a version rather than a filename. */
  exact: boolean
}

/** Characters a version runs through, and therefore ones a boundary must not sit inside. */
const INSIDE_A_NUMBER = /[0-9.+]/

/** What a version never starts or ends with, once the names are taken apart. */
const EDGE = /^[-_.+\s]+|[-_.+\s]+$/g

/**
 * Reads two builds of one artefact against each other, so the part that differs is the version.
 *
 * Mirrors steward's `VersionPair`: names must start alike, and neither end may land inside a number.
 */
export function versionJump(
  installed: string | undefined,
  wantedFile: string | undefined,
  wantedVersion: string | undefined,
): Jump | null {
  const fallbackTo = wantedVersion ?? wantedFile
  if (!installed || !fallbackTo) return null

  const pair = wantedFile ? split(installed, wantedFile) : null
  if (pair) return { from: pair[0], to: pair[1], exact: true }
  return { from: installed, to: fallbackTo, exact: false }
}

/** The two remainders, or `null` when the names do not come apart into a pair worth printing. */
function split(left: string, right: string): [string, string] | null {
  if (left === right) return null

  let head = 0
  while (head < left.length && head < right.length && left[head] === right[head]) head += 1
  // Back out of a number the prefix ended inside, so `…-1.` gives the `1.` back to the version.
  while (head > 0 && INSIDE_A_NUMBER.test(left[head - 1])) head -= 1
  // Nothing in common at the front: a hash against a filename, or a renamed jar.
  if (head === 0) return null

  let tail = 0
  while (
    tail < left.length - head &&
    tail < right.length - head &&
    left[left.length - 1 - tail] === right[right.length - 1 - tail]
  ) {
    tail += 1
  }
  // Back out of a number the suffix started inside, so `.0.jar` gives back `.0`.
  while (tail > 0 && INSIDE_A_NUMBER.test(left[left.length - tail])) tail -= 1

  const from = left.slice(head, left.length - tail).replace(EDGE, "")
  const to = right.slice(head, right.length - tail).replace(EDGE, "")
  if (!from || !to || from === to) return null
  if (!/[0-9]/.test(from) || !/[0-9]/.test(to)) return null
  return [from, to]
}
