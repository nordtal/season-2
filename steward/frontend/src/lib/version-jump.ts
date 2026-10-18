/**
 * The version pair behind two filenames, or the filenames themselves when they do not come apart.
 */
export type Jump = {
  /** What is installed: a version if the pair came apart, else the filename. */
  from: string
  /** What would be installed: the publisher's version, else the filename. */
  to: string
  /** Whether `from` is a version and not a filename. */
  exact: boolean
}

/** The characters a version runs through, and so the ones a boundary must not sit inside. */
const INSIDE_A_NUMBER = /[0-9.+]/

/** What a version never starts or ends with, once the names are taken apart. */
const EDGE = /^[-_.+\s]+|[-_.+\s]+$/g

/**
 * Returns the version pair of two builds of one artefact, read against each other so that the part that differs is the version.
 *
 * It mirrors Steward's `VersionPair`: the names must start alike, and neither end may land inside a number.
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

/**
 * Returns the two remainders, or `null` when the names do not come apart into a pair worth printing.
 */
function split(left: string, right: string): [string, string] | null {
  if (left === right) return null

  let head = 0
  while (head < left.length && head < right.length && left[head] === right[head]) head += 1
  // Backs out of a number that the prefix ended inside, so `…-1.` gives the `1.` back to the version.
  while (head > 0 && INSIDE_A_NUMBER.test(left[head - 1])) head -= 1
  // Nothing is common at the front: a hash against a filename, or a renamed jar.
  if (head === 0) return null

  let tail = 0
  while (
    tail < left.length - head &&
    tail < right.length - head &&
    left[left.length - 1 - tail] === right[right.length - 1 - tail]
  ) {
    tail += 1
  }
  // Backs out of a number that the suffix started inside, so `.0.jar` gives back `.0`.
  while (tail > 0 && INSIDE_A_NUMBER.test(left[left.length - tail])) tail -= 1

  const from = left.slice(head, left.length - tail).replace(EDGE, "")
  const to = right.slice(head, right.length - tail).replace(EDGE, "")
  if (!from || !to || from === to) return null
  if (!/[0-9]/.test(from) || !/[0-9]/.test(to)) return null
  return [from, to]
}
