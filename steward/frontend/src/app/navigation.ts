import { TERM_ICON } from "@/components/steward/term-icons"
import type { Icon } from "@phosphor-icons/react"
import type { LinkProps } from "@tanstack/react-router"

import { topologyOf } from "@/components/steward/network/topology"
import { useTopology } from "@/lib/queries"
import { t } from "@/lib/texts"

/** The one list of places, read by the sidebar, the palette and the breadcrumb; a route missing here is unreachable. */

export type NavEntry = {
  /** Stable id, used as a React key and as the command palette's search value. */
  id: string
  label: string
  /** One line of what the page is for, shown by the command palette and the placeholder. */
  note: string
  icon: Icon
  /** Words an admin might type that are not in the label. */
  keywords?: string[]
} & (
  | { to: "/services/$name"; params: { name: string } }
  | { to: Exclude<LinkProps["to"], undefined | "/services/$name">; params?: undefined }
)

export type NavGroup = {
  id: string
  /** The heading over the group, left out where a group's single entry names itself. */
  label?: string
  entries: NavEntry[]
}

/**
 * Four groups: `Services`, `Operations` and two unlabelled ones for the single pages around them.
 *
 * `services` are the names `/api/topology` serves, in the network table's order; {@link useNavigation} passes them.
 */
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
        label: name,
        to: "/services/$name",
        params: { name },
        note: t("steward.shell.service-note", { name }),
        icon: TERM_ICON.service,
        keywords: ["container", "log", "console", "restart"],
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
          /** The list page rather than a backup id, since "latest" is not a backup's name. */
          id: "operations-backups",
          label: t("steward.shell.page", { page: "backups" }),
          to: "/operations/backups",
          note: t("steward.shell.note", { page: "backups" }),
          icon: TERM_ICON.backup,
          keywords: ["backup", "archive", "snapshot", "retention", "storage box", "offsite", "restore"],
        },
        {
          id: "operations-alerts",
          label: t("steward.shell.page", { page: "alerts" }),
          to: "/alerts",
          note: t("steward.shell.note", { page: "alerts" }),
          icon: TERM_ICON.alert,
          keywords: ["alert", "issue", "problem", "warning", "down", "notification"],
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
          keywords: ["audit", "history", "trail", "log"],
        },
      ],
    },
  ]
}

/** The navigation with the services the stack serves, which list none until `/api/topology` has answered. */
export function useNavigation(): NavGroup[] {
  const topology = useTopology()
  return navigation(topology.data ? topologyOf(topology.data).names : [])
}
