import {
  ArchiveIcon,
  ArrowCircleUpIcon,
  ArrowClockwiseIcon,
  ArrowsClockwiseIcon,
  ArrowsCounterClockwiseIcon,
  BellIcon,
  BookOpenTextIcon,
  BoxArrowUpIcon,
  CalendarDotIcon,
  CalendarIcon,
  ChartBarIcon,
  CircleDashedIcon,
  ClockIcon,
  CreditCardIcon,
  CrownCrossIcon,
  CrownIcon,
  CubeIcon,
  FlagBannerIcon,
  FlagIcon,
  HandCoinsIcon,
  HardDrivesIcon,
  HashIcon,
  HourglassIcon,
  KeyIcon,
  LinkBreakIcon,
  LinkSimpleIcon,
  MegaphoneIcon,
  PackageIcon,
  PlayIcon,
  PlugIcon,
  PowerIcon,
  ProhibitInsetIcon,
  PulseIcon,
  ShieldCheckIcon,
  ShieldSlashIcon,
  SlidersHorizontalIcon,
  SparkleIcon,
  SpeakerHighIcon,
  SpinnerBallIcon,
  StopIcon,
  SwordIcon,
  TargetIcon,
  TerminalWindowIcon,
  TranslateIcon,
  TrophyIcon,
  UserIcon,
  UsersIcon,
  WarningIcon,
  WrenchIcon,
  XCircleIcon,
} from "@phosphor-icons/react"
import type { Icon } from "@phosphor-icons/react"

/**
 * The one icon of every domain term, drawn wherever the term appears: page, pill, field, table, tooltip and palette.
 *
 * An icon here belongs to its term alone; `one-icon-per-term.test.ts` fails when another file imports it.
 */
export const TERM_ICON = {
  overview: PulseIcon,
  service: HardDrivesIcon,
  console: TerminalWindowIcon,
  settings: SlidersHorizontalIcon,
  plugins: PlugIcon,
  update: ArrowCircleUpIcon,
  checkForUpdates: ArrowsCounterClockwiseIcon,
  localBuild: WrenchIcon,
  restart: ArrowClockwiseIcon,
  recreate: ArrowsClockwiseIcon,
  down: PowerIcon,
  start: PlayIcon,
  cancel: StopIcon,
  schedule: ClockIcon,
  nothingToDo: ProhibitInsetIcon,
  backup: ArchiveIcon,
  restore: BoxArrowUpIcon,
  alert: BellIcon,
  warning: WarningIcon,
  failed: XCircleIcon,
  season: CalendarIcon,
  seasonDates: CalendarDotIcon,
  phase: FlagIcon,
  translations: TranslateIcon,
  people: UsersIcon,
  person: UserIcon,
  grantAccess: ShieldCheckIcon,
  revokeAccess: ShieldSlashIcon,
  link: LinkSimpleIcon,
  unlink: LinkBreakIcon,
  admin: CrownIcon,
  revokeAdmin: CrownCrossIcon,
  playtime: HourglassIcon,
  pack: PackageIcon,
  securityKey: KeyIcon,
  payments: CreditCardIcon,
  settle: HandCoinsIcon,
  journal: BookOpenTextIcon,
  announcement: MegaphoneIcon,
  textChannel: HashIcon,
  voiceChannel: SpeakerHighIcon,
  round: SwordIcon,
  milestone: FlagBannerIcon,
  target: TargetIcon,
  borderDiameter: CircleDashedIcon,
  aura: SparkleIcon,
  spins: SpinnerBallIcon,
  statistic: ChartBarIcon,
  advancement: TrophyIcon,
  item: CubeIcon,
} as const satisfies Record<string, Icon>

export type Term = keyof typeof TERM_ICON

/** The settings keys whose value is a term, so a field shows the icon its value carries elsewhere. */
const FIELD_TERM: Record<string, Term> = {
  aura: "aura",
  "aura-budget": "aura",
  "spin-budget": "spins",
  target: "target",
  "border-diameter": "borderDiameter",
  "admin-unlocked": "admin",
}

/** The icon of the term a settings key holds, if it holds one. */
export function fieldIcon(key: string): Icon | undefined {
  const term = FIELD_TERM[key]
  return term === undefined ? undefined : TERM_ICON[term]
}

/** A field's label with its term's icon before it, where its key holds a term. */
export function FieldLabelIcon({ fieldKey, className }: { fieldKey: string; className?: string }) {
  const icons = { Icon: fieldIcon(fieldKey) }
  return icons.Icon ? <icons.Icon aria-hidden className={className ?? "size-3.5 shrink-0"} /> : null
}
