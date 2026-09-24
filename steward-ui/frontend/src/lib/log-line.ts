/**
 * One console line, taken apart for drawing.
 *
 * Three forms reach the window, none of them carrying ANSI codes (checked with `cat -v`,
 * 2026-09-20), each behind the timestamp Docker puts in front with `timestamps=1`:
 *
 * ```
 * [06:00:40] [Server thread/INFO]: [voicechat] Disconnecting client hmtill        Paper
 * [06:25:38] [Netty epoll Worker #2/INFO] [com.velocity…ConnectedPlayer]: …       Velocity
 * 04:46:21.317 [main] INFO  eu.nordtal.s2.discordbot.AccessBot - access-bot is up  Logback
 * ```
 *
 * The thread falls away - it stands in every line and never says anything. The source is the
 * `[plugin]` prefix, otherwise the last component of the logger, otherwise nothing. **What does not
 * fit stays raw**, unchanged: a stack trace, a plugin printing without a prefix, the answer to `help`.
 */

export type Level = "TRACE" | "DEBUG" | "INFO" | "WARN" | "ERROR"

export type ParsedLine =
  | { kind: "parsed"; time: string; level: Level; source: string; text: string }
  | { kind: "raw"; text: string }

/** `2026-09-23T21:43:12.726838683Z ` - what Docker puts in front of every line. */
const DOCKER_STAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z /

const PAPER = /^\[(\d{2}:\d{2}:\d{2})\] \[[^\]]*\/(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\](?: \[([^\]]+)\])?: (.*)$/s
const LOGBACK =
  /^(\d{2}:\d{2}:\d{2})\.\d{3} (?:\[[^\]]*\] )?(TRACE|DEBUG|INFO|WARN|ERROR)\s+(\S+) - (.*)$/s
/** `[voicechat] text` - Paper's plugin prefix, which sits inside the message. */
const PLUGIN_PREFIX = /^\[([A-Za-z0-9_.$\- ]{1,120})\] (.*)$/s

/** The line without Docker's own timestamp in front, i.e. what the service itself wrote. */
export function stripDockerStamp(line: string): string {
  return line.replace(DOCKER_STAMP, "")
}

function lastComponent(logger: string): string {
  const dot = logger.lastIndexOf(".")
  return dot < 0 ? logger : logger.slice(dot + 1)
}

function level(word: string): Level {
  return word === "FATAL" ? "ERROR" : (word as Level)
}

export function parseLogLine(line: string): ParsedLine {
  const text = stripDockerStamp(line)
  const paper = PAPER.exec(text)
  if (paper) {
    const [, time, word, logger, rest] = paper
    if (logger) {
      return { kind: "parsed", time, level: level(word), source: lastComponent(logger), text: rest }
    }
    const plugin = PLUGIN_PREFIX.exec(rest)
    if (plugin) {
      return {
        kind: "parsed",
        time,
        level: level(word),
        source: lastComponent(plugin[1]),
        text: plugin[2],
      }
    }
    return { kind: "parsed", time, level: level(word), source: "", text: rest }
  }
  const logback = LOGBACK.exec(text)
  if (logback) {
    const [, time, word, logger, rest] = logback
    return { kind: "parsed", time, level: level(word), source: lastComponent(logger), text: rest }
  }
  return { kind: "raw", text }
}

/** A raw line that carries on the one before it - a stack trace keeps the colour of its error. */
export function continuesPrevious(raw: string): boolean {
  return /^\s/.test(raw) || raw.startsWith("at ") || raw.startsWith("Caused by")
}
