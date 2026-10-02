import type { ConfigEntry } from "@/lib/api"
import { isColour } from "@/lib/references"
import { ReferenceValues } from "@/components/steward/reference-picker"

/** A section's filled settings, read only, each under its schema label; what names a game thing shows it. */
export function SectionSummary({ fields }: { fields: ConfigEntry[] }) {
  const shown = fields.filter(isFilled)
  if (shown.length === 0) return null
  const valueOf = (key: string) => fields.find((field) => field.key === key)?.value
  return (
    <dl className="grid grid-cols-[auto_minmax(0,1fr)] items-start gap-x-4 gap-y-1.5 text-xs">
      {shown.map((field) => (
        <div key={field.key} className="contents">
          <dt className="pt-0.5 text-muted-foreground">{field.label}</dt>
          <dd className="min-w-0">
            {field.refers && !isColour(field.refers) ? (
              <ReferenceValues
                reference={field.refers}
                values={field.kind === "LIST" ? (field.items ?? []) : [field.value ?? ""]}
                sibling={field.refers.dependsOn ? valueOf(field.refers.dependsOn) : undefined}
              />
            ) : field.kind === "LIST" ? (
              <span className="flex flex-wrap gap-x-2 font-mono">
                {(field.items ?? []).map((item, index) => (
                  <span key={`${item}-${index}`}>{item}</span>
                ))}
              </span>
            ) : (
              <span className={field.type === "INTEGER" || field.type === "DECIMAL" ? "tabular-nums" : undefined}>
                {shownValue(field)}
              </span>
            )}
          </dd>
        </div>
      ))}
    </dl>
  )
}

function isFilled(field: ConfigEntry): boolean {
  if (field.secret || field.kind === "SECTIONS" || field.kind === "MAP") return false
  return field.kind === "LIST" ? (field.items ?? []).length > 0 : (field.value ?? "").trim() !== ""
}

function shownValue(field: ConfigEntry): string {
  const value = field.value ?? ""
  if (field.type === "BOOLEAN") return value === "true" ? "Yes" : "No"
  if (field.type === "INTEGER" || field.type === "DECIMAL") {
    const number = Number(value)
    return Number.isFinite(number) ? number.toLocaleString("en") : value
  }
  return value
}
