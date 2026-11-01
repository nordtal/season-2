import { readFileSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { describe, expect, it } from "vitest"

import { type Breach, breaches, type JsonObject, type RuleNode, shown } from "@/lib/setting-rules"

/** The cases JUnit runs on the Java evaluator, so both evaluators find the same breaches. */
const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../../..")

type Case = {
  case: string
  schema: RuleNode
  values: JsonObject
  skipped?: string[]
  breaches: (Breach & { shown?: string })[]
}

const cases: Case[] = JSON.parse(
  readFileSync(
    path.join(repository, "settings/src/test/resources/eu/nordtal/season/settings/rule/rule-cases.json"),
    "utf8",
  ),
)

function strip({ setting, entries, rule, value }: Breach): Breach {
  return { setting, entries, rule, value }
}

describe("the declared rules", () => {
  it.each(cases.map((one) => [one.case, one] as const))("%s", (_, one) => {
    expect(breaches(one.schema, one.values, new Set(one.skipped ?? []))).toEqual(one.breaches.map(strip))
  })

  it.each(
    cases.flatMap((one) =>
      one.breaches.filter((breach) => breach.shown !== undefined).map((breach) => [breach.shown, one, breach] as const),
    ),
  )("show %s with the index of each entry", (expected, one, breach) => {
    expect(shown(one.schema, strip(breach))).toBe(expected)
  })
})

/** The `{rule, select, ...}` part of a text in a bundle file. */
function ruleCases(file: string, key: string): string {
  const line = readFileSync(path.join(repository, file), "utf8")
    .split("\n")
    .find((candidate) => candidate.startsWith(`${key}=`))
  if (line === undefined) throw new Error(`${file} has no ${key}`)
  return line.slice(line.indexOf("{rule, select,"))
}

describe("the wording of a broken rule", () => {
  it("is the database bundle's wording of a refused save, case by case", () => {
    expect(ruleCases("steward/src/main/resources/messages/steward/en.properties", "steward.settings.breach")).toBe(
      ruleCases("database/src/main/resources/messages/database/en.properties", "server.settings-refused"),
    )
  })
})
