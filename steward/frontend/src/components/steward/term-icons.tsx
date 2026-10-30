import {
  ArchiveIcon,
  ArrowCircleUpIcon,
  ArrowClockwiseIcon,
  ArrowsClockwiseIcon,
  ArrowsCounterClockwiseIcon,
  BankIcon,
  BellIcon,
  BookOpenTextIcon,
  BoxArrowUpIcon,
  CalendarDotIcon,
  CalendarIcon,
  ChartBarIcon,
  CircleDashedIcon,
  ClockIcon,
  CompassIcon,
  CreditCardIcon,
  CrownCrossIcon,
  CrownIcon,
  CubeIcon,
  DatabaseIcon,
  ElevatorIcon,
  FlagCheckeredIcon,
  FlagBannerIcon,
  FlagIcon,
  GameControllerIcon,
  GlobeSimpleIcon,
  HandCoinsIcon,
  HardDrivesIcon,
  HashIcon,
  HeartIcon,
  HourglassIcon,
  KeyIcon,
  LinkBreakIcon,
  LinkSimpleIcon,
  MegaphoneIcon,
  MoonIcon,
  NetworkIcon,
  PackageIcon,
  PasswordIcon,
  PlayIcon,
  PlugIcon,
  PowerIcon,
  ProhibitInsetIcon,
  PulseIcon,
  RobotIcon,
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
import type { ComponentType } from "react"

import { DiscordMark } from "@/app/discord-mark"

/**
 * An icon here belongs to its term alone. `one-icon-per-term.test.ts` fails when another file imports it.
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
  standby: MoonIcon,
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
  supporter: HeartIcon,
  revokeAdmin: CrownCrossIcon,
  playtime: HourglassIcon,
  pack: PackageIcon,
  securityKey: KeyIcon,
  authenticatorApp: PasswordIcon,
  payments: CreditCardIcon,
  settle: HandCoinsIcon,
  journal: BookOpenTextIcon,
  announcement: MegaphoneIcon,
  textChannel: HashIcon,
  voiceChannel: SpeakerHighIcon,
  round: FlagCheckeredIcon,
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

/** An icon as a list draws it: a Phosphor icon or a brand's mark. */
export type Glyph = ComponentType<{ className?: string; "aria-hidden"?: boolean }>

/**
 * Each service's own icon, which belongs to that service alone. A service missing here is drawn with the term `service`.
 */
export const SERVICE_ICON: Readonly<Record<string, Glyph>> = {
  caddy: GlobeSimpleIcon,
  proxy: NetworkIcon,
  limbo: ElevatorIcon,
  smp: GameControllerIcon,
  "hunger-games": SwordIcon,
  "discord-bot": DiscordMark,
  steward: CompassIcon,
  "steward-agent": RobotIcon,
  "steward-bunq": BankIcon,
  postgres: DatabaseIcon,
}

const FIELD_TERM: Record<string, Term> = {
  aura: "aura",
  "aura-budget": "aura",
  "duel-stake": "aura",
  "spin-budget": "spins",
  spins: "spins",
  target: "target",
  "border-diameter": "borderDiameter",
  "admin-unlocked": "admin",
}

export function fieldIcon(key: string): Icon | undefined {
  const term = FIELD_TERM[key]
  return term === undefined ? undefined : TERM_ICON[term]
}

export function FieldLabelIcon({ fieldKey, className }: { fieldKey: string; className?: string }) {
  const icons = { Icon: fieldIcon(fieldKey) }
  return icons.Icon ? <icons.Icon aria-hidden className={className ?? "size-3.5 shrink-0"} /> : null
}
