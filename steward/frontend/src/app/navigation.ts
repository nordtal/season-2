import { TERM_ICON, type Glyph } from "@/components/steward/term-icons"
import type { LinkProps } from "@tanstack/react-router"

import { serviceGlyph } from "@/components/steward/service-name"
import { topologyOf } from "@/components/steward/network/topology"
import { useServices, useTopology } from "@/lib/queries"
import { withRunningStandbys } from "@/lib/services"
import { serviceTitle } from "@/lib/words"
import { t } from "@/lib/texts"

/** A route missing here is unreachable. */

export type NavEntry = {
  id: string
  label: string
  note: string
  icon: Glyph
  keywords?: string[]
} & (
  | { to: "/services/$name"; params: { name: string } }
  | { to: Exclude<LinkProps["to"], undefined | "/services/$name">; params?: undefined }
)

export type NavGroup = {
  id: string
  label?: string
  entries: NavEntry[]
}

/** `services` holds the names that `/api/topology` serves, in the order of the network table, and the running standbys. */
export function navigation(services: readonly string[]): NavGroup[] {
  return [
    {
      id: "overview",
      entries: [
        {
          id: "overview",
          label: t("steward.shell.page", { page: "overview" }),
          to: "/",
          note: t("steward.shell.note", { page: "overview" }),
          icon: TERM_ICON.overview,
          keywords: ["home", "dashboard", "health", "overview", "status"],
        },
      ],
    },
    {
      id: "services",
      label: t("steward.shell.page", { page: "services" }),
      entries: services.map((name) => ({
        id: `service-${name}`,
        label: serviceTitle(name),
        to: "/services/$name",
        params: { name },
        note: t("steward.shell.service-note", { name: serviceTitle(name) }),
        icon: serviceGlyph(name),
        keywords: [name, "container", "log", "console", "restart"],
      })),
    },
    {
      id: "operations",
      label: t("steward.shell.page", { page: "operations" }),
      entries: [
        {
          id: "operations-updates",
          label: t("steward.shell.page", { page: "updates" }),
          to: "/operations/updates",
          note: t("steward.shell.note", { page: "updates" }),
          icon: TERM_ICON.update,
          keywords: ["update", "drift", "image", "plugin", "restart", "schedule"],
        },
        {
          /** The id of the list page, because "latest" is not a backup's name. */
          id: "operations-backups",
          label: t("steward.shell.page", { page: "backups" }),
          to: "/operations/backups",
          note: t("steward.shell.note", { page: "backups" }),
          icon: TERM_ICON.backup,
          keywords: ["backup", "archive", "snapshot", "retention", "storage box", "offsite", "restore"],
        },
      ],
    },
    {
      id: "the-rest",
      entries: [
        {
          id: "season",
          label: t("steward.shell.page", { page: "season" }),
          to: "/season",
          note: t("steward.shell.note", { page: "season" }),
          icon: TERM_ICON.season,
          keywords: ["season", "phase", "reset", "launch"],
        },
        {
          id: "translations",
          label: t("steward.shell.page", { page: "translations" }),
          to: "/translations",
          note: t("steward.shell.note", { page: "translations" }),
          icon: TERM_ICON.translations,
          keywords: ["translations", "texts", "messages", "language", "wording", "german"],
        },
        {
          id: "access",
          label: t("steward.shell.page", { page: "access" }),
          to: "/access",
          note: t("steward.shell.note", { page: "access" }),
          icon: TERM_ICON.people,
          keywords: ["access", "whitelist", "roles", "accounts", "players", "discord", "link"],
        },
        {
          id: "payments",
          label: t("steward.shell.page", { page: "payments" }),
          to: "/payments",
          note: t("steward.shell.note", { page: "payments" }),
          icon: TERM_ICON.payments,
          keywords: ["bunq", "contribution", "money", "payments"],
        },
        {
          id: "journal",
          label: t("steward.shell.page", { page: "journal" }),
          to: "/journal",
          note: t("steward.shell.note", { page: "journal" }),
          icon: TERM_ICON.journal,
          keywords: ["audit", "history", "trail", "log", "alert", "issue", "problem", "warning", "down", "run"],
        },
      ],
    },
  ]
}

export function useNavigation(): NavGroup[] {
  const topology = useTopology()
  const rows = useServices()
  const names = topology.data ? topologyOf(topology.data).names : []
  return navigation(withRunningStandbys(names, rows.data?.services ?? []))
}
