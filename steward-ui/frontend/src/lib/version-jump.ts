/**
 * The version pair behind two filenames, derived rather than guessed (season-2-ops/142).
 *
 * The owner asked on 2026-09-20 that the Available card read `v1.5 → v1.6` rather than
 * `Chunky-Bukkit-1.5.3.jar` against `1.6.0`. The obstacle is that **the worker does not know the
 * installed version**: a `Change` carries the installed *filename* and the wanted *file*, and
 * nothing on either side of that comparison is a version number. An invented `v1.5` would be worse
 * than an ugly filename, so nothing here parses a version out of one name on its own.
 *
 * What it does instead is read the two names **against each other**. Two builds of the same
 * artefact differ in the version and nowhere else, so the part that differs is the version, and
 * finding it is a comparison rather than a guess:
 *
 * ```
 * Chunky-Bukkit-1.5.3.jar
 * Chunky-Bukkit-1.6.0.jar        ->  1.5.3 → 1.6.0
 * ```
 *
 * <h2>Why the common prefix alone is not enough</h2>
 * The raw common prefix of those two is `Chunky-Bukkit-1.`, which would answer `5.3 → 6.0` and
 * drop the major version. So both ends are walked back out of the number they landed inside: the
 * prefix gives back its trailing digits and dots, the suffix gives back its leading ones, and the
 * remainder is the whole version on each side. `packetevents-spigot-2.13.0.jar` against
 * `…-2.14.0.jar` is the case that proves it matters - its raw prefix ends in the middle of `13`.
 *
 * <h2>When it refuses</h2>
 * A pair that does not come apart cleanly - no digit in a remainder, an empty one, or two names
 * that are the same - is answered with `exact: false` and the filename, which is the ticket's own
 * fallback: visible as a stopgap rather than dressed up as a version.
 *
 * And the two names have to **start** alike. Without that rule the resource pack - whose installed
 * side is a SHA-1 and whose wanted side is a zip's name - comes apart into "the whole hash" and
 * "the whole filename", both of which contain a digit, and is drawn as a version jump. Two names
 * that share no first character are not two builds of one artefact.
 *
 * <h2>Its twin in the worker</h2>
 * `steward-worker`'s `VersionPair` is the same rule in Java, for the report - which is what
 * Discord, the chat follower and a run's own page draw. The two are deliberate copies of one rule
 * and both carry the `packetevents-spigot-2.13.0.jar` case; a change to either is a change to both.
 */
export type Jump = {
  /** What is installed: a version when the pair came apart, the filename when it did not. */
  from: string
  /** What would be installed: the publisher's version, or the filename as a last resort. */
  to: string
  /** Whether `from` is a version rather than a filename. `false` means this is the fallback. */
  exact: boolean
}

/** Characters a version runs through, and therefore ones a boundary must not sit inside. */
const INSIDE_A_NUMBER = /[0-9.+]/

/** What a version never starts or ends with, once the two names have been taken apart. */
const EDGE = /^[-_.+\s]+|[-_.+\s]+$/g

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
  // Back out of any number the prefix ended inside, so `…-1.` gives the `1.` back to the version.
  while (head > 0 && INSIDE_A_NUMBER.test(left[head - 1])) head -= 1
  // Nothing in common at the front: a hash against a filename, or a publisher who renamed the jar.
  if (head === 0) return null

  let tail = 0
  while (
    tail < left.length - head &&
    tail < right.length - head &&
    left[left.length - 1 - tail] === right[right.length - 1 - tail]
  ) {
    tail += 1
  }
  // And out of the one the suffix started inside - `.0.jar` gives back `.0`.
  while (tail > 0 && INSIDE_A_NUMBER.test(left[left.length - tail])) tail -= 1

  const from = left.slice(head, left.length - tail).replace(EDGE, "")
  const to = right.slice(head, right.length - tail).replace(EDGE, "")
  if (!from || !to || from === to) return null
  if (!/[0-9]/.test(from) || !/[0-9]/.test(to)) return null
  return [from, to]
}
