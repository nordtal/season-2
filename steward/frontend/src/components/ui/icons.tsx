import type { JSX, SVGProps } from "react"

/**
 * Every icon of Steward is a Pixelarticons icon (MIT), except a brand's own logo such as Discord's. Each name
 * says what the icon means; the value is the Pixelarticons icon that draws it, as the theme studio chose it.
 * A comment marks a choice where the set has no exact match.
 */
export const PIXELARTICONS = {
  ArchiveIcon: "archive",
  ArrowCircleUpIcon: "arrow-up-box",
  ArrowClockwiseIcon: "redo",
  ArrowCounterClockwiseIcon: "undo",
  ArrowDownIcon: "arrow-down",
  ArrowElbowDownLeftIcon: "corner-down-left",
  ArrowLeftIcon: "arrow-left",
  ArrowRightIcon: "arrow-right",
  ArrowSquareInIcon: "arrow-down-box",
  ArrowSquareOutIcon: "external-link",
  ArrowUpIcon: "arrow-up",
  ArrowsClockwiseIcon: "reload",
  ArrowsCounterClockwiseIcon: "refresh",
  ArrowsLeftRightIcon: "arrows-horizontal",
  ArrowsSplitIcon: "git-branch",
  BankIcon: "university",
  BarricadeIcon: "road-sign",
  BedIcon: "bed",
  BellIcon: "bell",
  BellSlashIcon: "bell-off",
  BookOpenTextIcon: "book-open",
  BoxArrowUpIcon: "upload",
  BracketsCurlyIcon: "braces",
  CalendarIcon: "calendar",
  CalendarDotIcon: "calendar-today",
  CalendarPlusIcon: "calendar-plus",
  CalendarXIcon: "calendar-remove",
  CaretDownIcon: "chevron-down",
  CaretLeftIcon: "chevron-left",
  CaretRightIcon: "chevron-right",
  CaretUpIcon: "chevron-up",
  ChartBarIcon: "chart-bar",
  ChatTextIcon: "message-text",
  CheckIcon: "check",
  CheckCircleIcon: "checkbox-on",
  CircleIcon: "circle",
  CircleDashedIcon: "circle-pile",
  CircleNotchIcon: "loading",
  ClockIcon: "clock",
  ClockCountdownIcon: "hourglass",
  ClockCounterClockwiseIcon: "calendar-sort-ascending", // no history icon; calendar-sort-ascending approximates
  CodeIcon: "code",
  CompassIcon: "compass",
  CopyIcon: "copy",
  CreditCardIcon: "credit-card",
  CrownIcon: "crown",
  CrownCrossIcon: "crown-sharp", // no crossed crown; sharp variant of crown
  CubeIcon: "box",
  CursorClickIcon: "pointer",
  DatabaseIcon: "database",
  DoorIcon: "door-closed",
  DotsThreeIcon: "more-horizontal",
  DownloadIcon: "download",
  DownloadSimpleIcon: "cloud-download", // cloud-download to stay distinct
  ElevatorIcon: "arrow-down-diamond", // no elevator; arrow-down-diamond
  EraserIcon: "eraser",
  EyeIcon: "eye",
  EyeSlashIcon: "eye-off",
  FingerprintIcon: "scan-barcode", // no fingerprint
  FlagBannerIcon: "flag",
  FlagCheckeredIcon: "goal",
  FolderIcon: "folder",
  FunnelSimpleIcon: "filter",
  GameControllerIcon: "gamepad",
  GearIcon: "settings-cog",
  GearSixIcon: "gear",
  GlobeSimpleIcon: "globe",
  HandIcon: "hand",
  HandCoinsIcon: "coins",
  HardDrivesIcon: "server",
  HashIcon: "hash",
  HashStraightIcon: "hd", // no second hash; hd shape approximates
  HeartIcon: "heart",
  HourglassIcon: "hourglass-glyph", // hourglass-glyph vs hourglass for clock-countdown
  InfoIcon: "circle-info",
  KeyIcon: "key",
  LightningIcon: "zap",
  LinkIcon: "link",
  LinkBreakIcon: "unlink",
  LinkSimpleIcon: "link-sharp", // link-sharp variant of link
  ListIcon: "list",
  ListMagnifyingGlassIcon: "text-search",
  LockIcon: "lock",
  LockOpenIcon: "lock-open",
  LockSimpleIcon: "lock-sharp", // lock-sharp variant
  MagnifyingGlassIcon: "search",
  MegaphoneIcon: "megaphone",
  MinusIcon: "minus",
  MoonIcon: "moon",
  NetworkIcon: "cloud-server", // cloud-server for proxy
  PackageIcon: "package",
  PaletteIcon: "paint-bucket",
  PaperPlaneTiltIcon: "send",
  PasswordIcon: "text-cursor-input", // text-cursor-input
  PencilIcon: "edit",
  PencilSimpleIcon: "pencil", // pencil vs edit
  PlayIcon: "play",
  PlugIcon: "plug",
  PlugsIcon: "plug-solid", // plug-solid as variant of plug
  PlusIcon: "plus",
  PowerIcon: "power",
  ProhibitIcon: "cancel",
  ProhibitInsetIcon: "minus-box", // nothing to do: a box with a dash, apart from cancel
  PulseIcon: "audio-waveform",
  PuzzlePieceIcon: "blocks",
  QuestionIcon: "circle-question",
  RepeatIcon: "repeat",
  RobotIcon: "robot",
  RocketLaunchIcon: "ship",
  ShieldCheckIcon: "shield",
  ShieldSlashIcon: "shield-off",
  ShieldWarningIcon: "warning-diamond",
  ShuffleIcon: "shuffle",
  SidebarIcon: "layout-sidebar-left",
  SignOutIcon: "logout",
  SignpostIcon: "directions",
  SlidersHorizontalIcon: "sliders-horizontal",
  SmileyIcon: "smile",
  SparkleIcon: "sparkle",
  SpeakerHighIcon: "volume-3",
  SpinnerIcon: "loader",
  SpinnerBallIcon: "loading-3",
  StopIcon: "stop",
  SwapIcon: "sort-horizontal",
  SwordIcon: "sword",
  TargetIcon: "bullseye",
  TerminalWindowIcon: "terminal",
  TextAaIcon: "text-start-a",
  TextBIcon: "letter-b",
  TextItalicIcon: "letter-i",
  TextStrikethroughIcon: "letter-s",
  TextTSlashIcon: "text-start-t",
  TextUnderlineIcon: "letter-u",
  TimerIcon: "alarm-clock",
  TranslateIcon: "languages",
  TrashIcon: "trash",
  TrayIcon: "inbox",
  TrophyIcon: "trophy",
  UploadSimpleIcon: "cloud-upload", // cloud-upload to stay distinct
  UserIcon: "user",
  UsersIcon: "users",
  WarningIcon: "alert",
  WrenchIcon: "tools",
  XIcon: "close",
  XCircleIcon: "close-box", // the set has no circled x
} as const

export type IconName = keyof typeof PIXELARTICONS

/**
 * The paths of each Pixelarticons icon drawn above, copied from `@iconify-json/pixelarticons` so that only these
 * ship. A test compares them with the set.
 */
export const PIXELARTICON_PATHS: Readonly<Record<(typeof PIXELARTICONS)[IconName], readonly string[]>> = {
  "alarm-clock": [
    "M8 5h8v2H8zm0 14h8v2H8zM6 7h2v2H6zm0 10h2v2H6zM16 7h2v2h-2zm0 10h2v2h-2zM4 9h2v8H4zm14 0h2v8h-2zM4 2h2v2H4zm0 17h2v2H4zm14 0h2v2h-2zm0-17h2v2h-2zM2 4h2v2H2zm18 0h2v2h-2zm-9 5h2v4h-2zm2 4h2v2h-2z",
  ],
  alert: [
    "M13 1h-2v2H9v2H7v2H5v2H3v2H1v2h2v2h2v2h2v2h2v2h2v2h2v-2h2v-2h2v-2h2v-2h2v-2h2v-2h-2V9h-2V7h-2V5h-2V3h-2zm0 2v2h2v2h2v2h2v2h2v2h-2v2h-2v2h-2v2h-2v2h-2v-2H9v-2H7v-2H5v-2H3v-2h2V9h2V7h2V5h2V3zm0 4h-2v6h2zm0 8h-2v2h2z",
  ],
  archive: ["M3 2h18v2H3zm0 5h18v2H3zM1 4h2v3H1zm20 0h2v3h-2zm-2 5h2v11h-2zM3 9h2v11H3zm2 11h14v2H5zm4-9h6v2H9z"],
  "arrow-down": ["M13 12h6v2h-2v2h-2v2h-2v2h-2v-2H9v-2H7v-2H5v-2h6V4h2z"],
  "arrow-down-box": [
    "M4 2h16v2H4zm0 18h16v2H4zM2 4h2v16H2zm18 0h2v16h-2zm-9 12h2v2h-2zm0-10h2v6h-2zm-2 8h6v2H9zm-2-2h10v2H7z",
  ],
  "arrow-down-diamond": [
    "M11 16h2v2h-2zm0-10h2v6h-2zm-2 8h6v2H9zm-2-2h10v2H7zm4-11h2v2h-2zm2 2h2v2h-2zm-2 0H9v2h2zm4 2h2v2h-2zM9 5H7v2h2zm8 2h2v2h-2zM7 7H5v2h2zm12 2h2v2h-2zM5 9H3v2h2zm16 2h2v2h-2zM3 11H1v2h2zm16 2h2v2h-2zM5 13H3v2h2zm12 2h2v2h-2zM7 15H5v2h2zm8 2h2v2h-2zm-6 0H7v2h2zm4 2h2v2h-2zm-2 0H9v2h2zm0 2h2v2h-2z",
  ],
  "arrow-left": ["M20 11v2H4v-2zM8 13v2H6v-2zm2 2v2H8v-2zm2 2v2h-2v-2zm-4-6V9H6v2z", "M10 15V7H8v8zm2 2V5h-2v12z"],
  "arrow-right": ["M4 11v2h16v-2zm12 2v2h2v-2zm-2 2v2h2v-2zm-2 2v2h2v-2zm4-6V9h2v2z", "M14 15V7h2v8zm-2 2V5h2v12z"],
  "arrow-up": ["M11 20h2V4h-2zm2-12h2V6h-2zm2 2h2V8h-2zm2 2h2v-2h-2zm-6-4H9V6h2z", "M15 10H7V8h8zm2 2H5v-2h12z"],
  "arrow-up-box": [
    "M4 2h16v2H4zm0 18h16v2H4zM2 4h2v16H2zm18 0h2v16h-2zm-8.933 4.009h2v-2h-2zm0 10h2v-6h-2zm-2-8h6v-2h-6zm-2 2h10v-2h-10z",
  ],
  "arrows-horizontal": [
    "M13 13v-2h10v2zm6 2v-2h2v2zm-2 2v-2h2v2zm2-6V9h2v2z",
    "M17 15V7h2v8zm-6-2v-2H1v2zm-6 2v-2H3v2zm2 2v-2H5v2zm-2-6V9H3v2z",
    "M7 15V7H5v8z",
  ],
  "audio-waveform": [
    "M3 7h2v5H3zm4 0h2v13H7zm4-3h2v16h-2zm4 0h2v13h-2zM5 5h2v2H5zm4 15h2v2H9zm4-18h2v2h-2zm4 15h2v2h-2zm2-5h2v5h-2zm2-2h2v2h-2zM1 12h2v2H1z",
  ],
  bed: ["M2 4h2v16H2zm18 6h2v10h-2z", "M2 16h20v2H2zm2-8h16v2H4zm2 2h2v6H6z"],
  bell: [
    "M9 2h6v2H9zM7 4h2v2H7zm8 0h2v2h-2zM5 6h2v7H5zm12 0h2v7h-2zM3 13h2v4H3zm16 0h2v4h-2z",
    "M3 15h18v2H3zm5 3h2v2H8zm6 0h2v2h-2zm-4 2h4v2h-4z",
  ],
  "bell-off": [
    "M9 2h6v2H9zm6 2h2v2h-2zM5 6h2v7H5zm12 0h2v6h-2zM3 13h2v4H3z",
    "M3 15h14v2H3zm5 3h2v2H8zm6 0h2v2h-2zm-4 2h4v2h-4zM5 4h2v2H5zm2 2h2v2H7zm2 2h2v2H9zm2 2h2v2h-2zm2 2h2v2h-2z",
    "M15 14h2v2h-2zm2 2h2v2h-2zm2 2h2v2h-2zM3 2h2v2H3z",
  ],
  blocks: [
    "M15 1h6v2h-6zm-2 2h2v6h-2zm2 6h6v2h-6zm6-6h2v6h-2zM3 5h6v2H3zM1 7h2v14H1zm2 14h14v2H3zm14-6h2v6h-2zM3 13h14v2H3z",
    "M9 7h2v14H9z",
  ],
  "book-open": [
    "M2 3h9v2H2zM0 19h11v2H0zM13 3h9v2h-9zm0 16h11v2H13zM11 5h2v18h-2zM0 5h2v14H0zm22 0h2v14h-2zm-7 2h5v2h-5zm0 4h5v2h-5zm0 4h2v2h-2z",
  ],
  box: [
    "M14 4h4v2h-4zm-4-2h4v2h-4zM6 8h4v2H6zm0 10h4v2H6zm4-8h4v2h-4zm0 10h4v2h-4zm4-12h4v2h-4zm0 10h4v2h-4zM6 4h4v2H6zM2 6h4v2H2zm0 10h4v2H2zM18 6h4v2h-4zm0 10h4v2h-4z",
    "M2 6h2v12H2zm18 0h2v12h-2zm-8 6h2v8h-2z",
  ],
  braces: [
    "M6 4h4v2H6zm12 0h-4v2h4zM6 20h4v-2H6zm12 0h-4v-2h4zM4 6h2v5H4zm16 0h-2v5h2zM4 18h2v-5H4zm16 0h-2v-5h2zM2 11h2v2H2zm20 0h-2v2h2z",
  ],
  bullseye: [
    "M18 2H6v2H4v2H2v12h2v2h2v2h12v-2h2v-2h2V6h-2V4h-2zm0 2v2h2v12h-2v2H6v-2H4V6h2V4zm-8 6h4v4h-4zM8 6h8v2H8zm0 10H6V8h2zm8 0v2H8v-2zm0 0h2V8h-2z",
  ],
  calendar: [
    "M5 4h14v2H5zm0 16h14v2H5zM3 10h2v10H3zm0-4h2v2H3zm16 0h2v2h-2zm0 4h2v10h-2zM3 8h18v2H3zm12-6h2v2h-2zM7 2h2v2H7z",
  ],
  "calendar-plus": ["M15 2h2v2h4v18H3V4h4V2h2v2h6zM9 6H5v2h14V6zm-4 4v10h14V10zm6 2h2v2h2v2h-2v2h-2v-2H9v-2h2z"],
  "calendar-remove": [
    "M17 2h-2v2H9V2H7v2H3v18h18V4h-4zM7 6h12v2H5V6zM5 20V10h14v10zm6-4H9v2h2zm0-2v-2H9v2zm2 0h-2v2h2v2h2v-2h-2zm0 0v-2h2v2z",
  ],
  "calendar-sort-ascending": [
    "M10 5H8v2H4V5H2v2H0v12h12V7h-2zM2 9h8v2H2zm0 8v-4h8v4zM20 7h-2v8h-2v-2h-2v2h2v2h2v2h2v-2h2v-2h2v-2h-2v2h-2z",
  ],
  "calendar-today": ["M17 2h-2v2H9V2H7v2H3v18h18V4h-4zM7 6h12v2H5V6zM5 20V10h14v10zm6-4v-4H7v4z"],
  cancel: [
    "M6 2h12v2H6zm0 18h12v2H6zM2 6h2v12H2zm18 0h2v12h-2zm-2-2h2v2h-2zm-2 2h2v2h-2zm-2 2h2v2h-2zm-2 2h2v2h-2zm-2 2h2v2h-2zm-2 2h2v2H8zm-2 2h2v2H6zm12 2h2v2h-2zM4 4h2v2H4zm0 14h2v2H4z",
  ],
  "chart-bar": ["M13 5h2v14h-2zm-2 4H9v10h2zm-4 4H5v6h2zm12 0h-2v6h2z"],
  check: ["M10 18H8v-2h2zm-2-2H6v-2h2zm4-2v2h-2v-2zm-6 0H4v-2h2zm8 0h-2v-2h2zm2-2h-2v-2h2zm2-2h-2V8h2zm2-2h-2V6h2z"],
  "checkbox-on": [
    "M4 2h16v2H4zm0 18h16v2H4zM2 4h2v16H2zm18 0h2v16h-2zM7 12h2v2H7zm2 2h2v2H9zm2-2h2v2h-2zm2-2h2v2h-2zm2-2h2v2h-2z",
  ],
  "chevron-down": ["M13 16h-2v-2h2zm-2-2H9v-2h2zm4 0h-2v-2h2zm-6-2H7v-2h2zm8 0h-2v-2h2zM7 10H5V8h2zm12 0h-2V8h2z"],
  "chevron-left": ["M8 13v-2h2v2zm2-2V9h2v2zm0 4v-2h2v2zm2-6V7h2v2zm0 8v-2h2v2zm2-10V5h2v2zm0 12v-2h2v2z"],
  "chevron-right": ["M16 13v-2h-2v2zm-2-2V9h-2v2zm0 4v-2h-2v2zm-2-6V7h-2v2zm0 8v-2h-2v2zM10 7V5H8v2zm0 12v-2H8v2z"],
  "chevron-up": ["M13 8h-2v2h2zm-2 2H9v2h2zm4 0h-2v2h2zm-6 2H7v2h2zm8 0h-2v2h2zM7 14H5v2h2zm12 0h-2v2h2z"],
  circle: ["M6 2h12v2H6zm0 18h12v2H6zM2 6h2v12H2zm18 0h2v12h-2zm-2-2h2v2h-2zm0 14h2v2h-2zM4 4h2v2H4zm0 14h2v2H4z"],
  "circle-info": [
    "M18 22H6v-2h12zM6 20H4v-2h2zm14 0h-2v-2h2zM4 18H2V6h2zm18 0h-2V6h2zm-9-1h-2v-6h2zm0-8h-2V7h2zM6 6H4V4h2zm14 0h-2V4h2zm-2-2H6V2h12z",
  ],
  "circle-pile": [
    "M11 2h2v2h-2zM7 9h2v2H7zm8 0h2v2h-2zM3 16h2v2H3zm8 0h2v2h-2zm8 0h2v2h-2zM9 4h2v2H9zm-4 7h2v2H5zm8 0h2v2h-2zM1 18h2v2H1zm8 0h2v2H9zm8 0h2v2h-2zM11 6h2v2h-2zm-4 7h2v2H7zm8 0h2v2h-2zM3 20h2v2H3zm8 0h2v2h-2zm8 0h2v2h-2zM13 4h2v2h-2zm-4 7h2v2H9zm8 0h2v2h-2zM5 18h2v2H5zm8 0h2v2h-2zm8 0h2v2h-2z",
  ],
  "circle-question": [
    "M18 22H6v-2h12zM6 20H4v-2h2zm14 0h-2v-2h2zM4 18H2V6h2zm9 0h-2v-2h2zm9 0h-2V6h2zm-7-5h-2v2h-2v-4h4zm2-2h-2V8h2zm-8-1H7V8h2zm6-2H9V6h6zM6 6H4V4h2zm14 0h-2V4h2zm-2-2H6V2h12z",
  ],
  clock: [
    "M6 2h12v2H6zM2 6h2v12H2zm18 0h2v12h-2zm-2-2h2v2h-2zM4 4h2v2H4zm2 18h12v-2H6zm12-2h2v-2h-2zM4 20h2v-2H4zm7-14h2v7h-2zm2 7h2v2h-2zm2 2h2v2h-2z",
  ],
  close: [
    "M7 19H5v-2h2zm12 0h-2v-2h2zM9 15v2H7v-2zm8 2h-2v-2h2zm-6-2H9v-2h2zm4 0h-2v-2h2zm-2-2h-2v-2h2zm-2-2H9V9h2zm4 0h-2V9h2zM9 9H7V7h2zm8 0h-2V7h2zM7 7H5V5h2zm12 0h-2V5h2z",
  ],
  "close-box": [
    "M5 3H3v18h18V3zm14 2v14H5V5zm-8 4H9V7H7v2h2v2h2v2H9v2H7v2h2v-2h2v-2h2v2h2v2h2v-2h-2v-2h-2v-2h2V9h2V7h-2v2h-2v2h-2z",
  ],
  "cloud-download": [
    "M10 4h6v2h-6zM8 8V6h2v2zm-4 2V8h4v2zm-2 2v-2h2v2zm0 6H0v-6h2zm0 0h5v2H2zM18 8h-2V6h2zm4 4h-4V8h2v2h2zm0 6v-6h2v6zm0 0v2h-5v-2zm-11 2h2v-2h2v-2h2v-2h-4V9h-2v5H7v2h2v2h2z",
  ],
  "cloud-server": [
    "M20 6h-2v2h2zm2 2h-2v4h2zm-2 4H4v2h16zM4 8H2v4h2zm4-2H4v2h4zm8-4h-6v2h6zm-6 2H8v2h2zm0 4H8v2h2zm8-4h-2v2h2zm0 4h-2v2h2zm-7 8h2v2h-2zm0 4h2v2h-2zm-7-2h7v2H4zm9 0h7v2h-7zm-2-4h2v2h-2z",
  ],
  "cloud-upload": [
    "M10 4h6v2h-6zM8 8V6h2v2zm-4 2V8h4v2zm-2 2v-2h2v2zm0 6H0v-6h2zm0 0h7v2H2zM18 8h-2V6h2zm4 4h-4V8h2v2h2zm0 6v-6h2v6zm0 0v2h-7v-2zM11 9h2v2h2v2h2v2h-4v5h-2v-5H7v-2h2v-2h2z",
  ],
  code: [
    "M11 18H9v-4h2zm-4-1H5v-2h2zm12-2v2h-2v-2zM5 15H3v-2h2zm16 0h-2v-2h2zm-8-1h-2v-4h2zM3 13H1v-2h2zm20 0h-2v-2h2zM5 11H3V9h2zm16 0h-2V9h2zm-6-1h-2V6h2zM7 9H5V7h2zm12 0h-2V7h2z",
  ],
  coins: [
    "M6 2h6v2H6zM4 4h2v2H4zm8 0h2v2h-2zm-8 8h2v2H4zm8 0h2v2h-2zm-6 2h6v2H6zM2 6h2v6H2zm12 0h2v6h-2z",
    "M14 8h4v2h-4zm-4 10h2v2h-2zm8-8h2v2h-2zm-6 10h2v2h-2zm6-2h2v2h-2z",
    "M12 20h6v2h-6zm-4-6h2v4H8zm12-2h2v6h-2zM7 6h4v2H7z",
    "M9 6h2v6H9zm6 8h2v4h-2zm-1-2h3v2h-3z",
  ],
  compass: [
    "M18 22H6v-2h12zM6 20H4v-2h2zm14 0h-2v-2h2zm-10-4h2v2h-2v1H8v-9h2zm-6 2H2V6h2zm18 0h-2V6h2zm-8-2h-2v-2h2zm2-2h-2V8h-2V6h2V5h2zm-3-1h-2v-2h2zm-1-3h-2V8h2zM6 6H4V4h2zm14 0h-2V4h2zm-2-2H6V2h12z",
  ],
  copy: ["M8 6h12v2H8zM4 2h12v2H4zm2 6h2v12H6zM2 4h2v12H2zm6 16h12v2H8zM20 8h2v12h-2zm-4-4h2v2h-2zM4 16h2v2H4z"],
  "corner-down-left": ["M18 16H4v-2h14zm2-2h-2V4h2z", "M8 10h2v8H8zm-2 2h2v2H6zm0 4h2v2H6zm2 2h2v2H8z"],
  "credit-card": ["M4 4h16v2H4zm0 14h16v2H4zM2 6h2v12H2zm18 0h2v12h-2zM4 8h16v4H4zm2 6h6v2H6z"],
  crown: [
    "M3 3h2v12H3zm16 0h2v12h-2zm-8 0h2v2h-2zM9 5h2v2H9zM5 5h2v2H5z",
    "M3 3h2v2H3zm4 4h2v2H7zm6-2h2v2h-2zm2 2h2v2h-2zm2-2h2v2h-2zM5 15h14v2H5zm-2 4h18v2H3z",
  ],
  "crown-sharp": [
    "M3 3h2v12H3zm16 0h2v12h-2zm-8 0h2v2h-2zM9 5h2v2H9zM5 5h2v2H5z",
    "M3 3h2v2H3zm4 4h2v2H7zm6-2h2v2h-2zm2 2h2v2h-2zm2-2h2v2h-2zM3 15h18v2H3zm0 4h18v2H3z",
  ],
  database: [
    "M2 6h2v4H2zm0 4h2v4H2zm0 4h2v4H2zm18-8h2v4h-2zm0 4h2v4h-2zm0 4h2v4h-2zM4 4h4v2H4zm0 8h4v-2H4zm0 4h4v-2H4zm0 4h4v-2H4zM16 4h4v2h-4zm0 8h4v-2h-4zm0 4h4v-2h-4zm0 4h4v-2h-4zM8 2h8v2H8zm0 12h8v-2H8zm0 4h8v-2H8zm0 4h8v-2H8z",
  ],
  directions: [
    "M2 2h2v2H2zm2 2h2v2H4zm2-2h2v2H6zM2 6h2v2H2zm4 0h2v2H6zm11 9h3v2h-3zm-2 2h2v3h-2zm2 3h3v2h-3zm3-3h2v3h-2zM15 2h2v10h-2zm-2 2h2v2h-2z",
    "M11 6h9v2h-9z",
    "M19 6h2v2h-2zm-2-2h2v2h-2zM6 12h9v2H6zm-2 2h2v4H4zm0 6h2v2H4z",
  ],
  "door-closed": ["M3 19h18v2H3zM5 5h2v14H5zm2-2h10v2H7zm10 2h2v14h-2zm-8 6h2v2H9z"],
  download: [
    "M21 15v4h-2v-4zm-2 4v2H5v-2zM5 15v4H3v-4zm8-12v14h-2V3z",
    "M7 11v2h10v-2zm2 2v2h2v-2zm4 0v2h2v-2z",
    "M15 11v2h2v-2z",
  ],
  edit: [
    "M18 2h-2v2h-2v2h-2v2h-2v2H8v2H6v2H4v2H2v6h6v-2h2v-2h2v-2h2v-2h2v-2h2v-2h2V8h2V6h-2V4h-2zm0 8h-2v2h-2v2h-2v2h-2v2H8v-2H6v-2h2v-2h2v-2h2V8h2V6h2v2h2zM6 16H4v4h4v-2H6z",
  ],
  eraser: [
    "M15 18h6v2H7v-2h6v-2h2zm-8 0H5v-2h2zm-2-2H3v-2h2zm12 0h-2v-2h2zM7 14H5v-2h2zm8 0h-2v-2h2zm4 0h-2v-2h2zM9 12H7v-2h2zm4 0h-2v-2h2zm8 0h-2v-2h2zm-10-2H9V8h2zm8 0h-2V8h2zm-6-2h-2V6h2zm4 0h-2V6h2zm-2-2h-2V4h2z",
  ],
  "external-link": [
    "M11 5H5v2h6zM5 7H3v12h2zm12 12H5v2h12zm2-6h-2v6h2zm-8 0H9v2h2zm2-2h-2v2h2zm2-2h-2v2h2zm2-2h-2v2h2zm2-2h-2v2h2zm2-2h-2v8h2z",
    "M21 3h-8v2h8z",
  ],
  eye: [
    "M16 20H8v-2h8zm-8-2H4v-2h4zm12 0h-4v-2h4zM4 16H2v-2h2zm10-6h-2v2h2zh2v4h-2v2h-4v-2H8v-4h2V8h4zm8 6h-2v-2h2zM2 14H0v-4h2zm22 0h-2v-4h2zM4 10H2V8h2zm18 0h-2V8h2zM8 8H4V6h4zm12 0h-4V6h4zm-4-2H8V4h8z",
  ],
  "eye-off": [
    "M0 10h2v4H0zm24 0h-2v4h2zm-8 0h-2v2h2zm-6 0H8v4h2zM2 8h2v2H2zm0 8h2v-2H2zm20-8h-2v2h2zm0 8h-2v-2h2zM4 6h4v2H4zm0 12h4v-2H4zM20 6h-4v2h4zM10 4h6v2h-6zM8 20h8v-2H8zm4-12h2v2h-2zm-2 6h4v2h-4zM8 8h2v2H8zm2 2h2v4h-2zm2 2h2v2h-2z",
    "M6 6h2v2H6zM4 4h2v2H4zM2 2h2v2H2zm12 12h2v2h-2zm2 2h2v2h-2zm2 2h2v2h-2zm2 2h2v2h-2z",
  ],
  filter: ["M11 20h2v2H9V12h2zm4 0h-2v-8h2zm-6-8H7v-2h2zm8 0h-2v-2h2zM7 10H5V8h2zm12 0h-2V8h2zm2-2h-2V4H5v4H3V2h18z"],
  flag: ["M4 2h2v20H4z", "M4 4h16v2H4zm12 2h2v2h-2zm-2 2h2v2h-2zm2 2h2v2h-2zM4 12h16v2H4z"],
  folder: ["M4 4h6v2H4zm0 14h16v2H4zM20 8h2v10h-2zM2 6h2v12H2zm8 0h10v2H10z"],
  gamepad: ["M4 4h16v2H4zm0 14h16v2H4zM2 6h2v12H2zm18 0h2v12h-2zM8 9h2v6H8z", "M6 11h6v2H6zm8-2h2v2h-2zm2 4h2v2h-2z"],
  gear: [
    "M18 22h-5v2h-2v-2H6v-2h12zM4 22H2v-2h2zm18 0h-2v-2h2zM6 20H4v-2h2zm14 0h-2v-2h2zM4 18H2v-5H0v-2h2V6h2zm4 0H6v-2h2zm14-7h2v2h-2v5h-2v-5h-4v3H8V8h8v3h4V6h2zm-12-1v4h4v-4zM8 8H6V6h2zM6 6H4V4h2zm14 0h-2V4h2zM4 4H2V2h2zm9-2h5v2H6V2h5V0h2zm9 2h-2V2h2z",
  ],
  "git-branch": [
    "M4 14h4v2H4zm0 6h4v2H4zm-2-4h2v4H2zm6 0h2v4H8zm8-14h4v2h-4zm0 6h4v2h-4zm-2-4h2v4h-2zm6 0h2v4h-2zm-8 13h5v2h-5zm5-5h2v5h-2zM5 2h2v10H5z",
  ],
  globe: [
    "M6 2h12v2H6zm0 18h12v2H6zM4 4h2v2H4zm5 0h2v2H9zm0 14h2v2H9zm4 0h2v2h-2zM7 6h2v12H7zm8 0h2v12h-2zm-2-2h2v2h-2zm7 0h-2v2h2zM2 6h2v12H2zm20 0h-2v12h2zM4 18h2v2H4zm16 0h-2v2h2z",
    "M3 11h18v2H3z",
  ],
  goal: [
    "M6 2h2v2H6zm0 18h12v2H6zm12-2h2v2h-2zM4 18h2v2H4zM4 4h2v2H4zm16 10h2v4h-2zM2 6h2v12H2zm6 10h8v2H8zM6 8h2v8H6zm10 6h2v2h-2zM10 2h2v12h-2z",
    "M10 2h6v2h-6zm0 6h6v2h-6zm6-4h6v2h-6zm0 6h6v2h-6zm4-4h2v4h-2z",
  ],
  hand: [
    "M21 7h2v5h-2zm-4-2h2v7h-2zm-4-2h2v8h-2zM9 3h2v8H9zM5 5h2v8H5zm14 0h2v2h-2zm-4-2h2v2h-2zm-4-2h2v2h-2zM7 3h2v2H7zm-4 8h2v2H3zm-2 2h2v2H1zm0 2h2v2H1zm2 2h2v2H3zm2 2h2v2H5zm2 2h12v2H7zm12-2h2v2h-2zm2-7h2v7h-2zM5 13h2v2H5zm2 2h2v2H7z",
  ],
  hash: ["M9 3h2v5H9zm6 0h2v5h-2zm-7 7h2v4H8zm6 0h2v4h-2zm-7 6h2v5H7zm6 0h2v5h-2zM3 8h18v2H3zm0 6h18v2H3z"],
  hd: ["M3 7h2v4h4V7h2v10H9v-4H5v4H3zm10 8V7h6v2h-4v6h4v2h-6zm6 0V9h2v6z"],
  heart: [
    "M13 22h-2v-2h2zm-2-2H9v-2h2zm4 0h-2v-2h2zm-6-2H7v-2h2zm8 0h-2v-2h2zM7 16H5v-2h2zm12 0h-2v-2h2zM5 14H3v-2h2zm16 0h-2v-2h2zM3 12H1V6h2zm20 0h-2V6h2zM13 8h-2V6h2zM5 6H3V4h2zm6 0H9V4h2zm4 0h-2V4h2zm6 0h-2V4h2zM9 4H5V2h4zm10 0h-4V2h4z",
  ],
  hourglass: [
    "M16 22H8v-2h8zm-8-2H6v-4h2zm10 0h-2v-4h2zm-8-4H8v-2h2zm6 0h-2v-2h2zm-6-6h4v4h-4zm0 0H8V8h2zm6 0h-2V8h2zM8 8H6V4h2zm10 0h-2V4h2zm-2-4H8V2h8z",
  ],
  "hourglass-glyph": ["M16 16h2v6H6v-6h2v-2h2v-4H8V8H6V2h12v6h-2v2h-2v4h2z"],
  inbox: ["M2 4h2v16H2zm2 16h16v2H4zM20 4h2v16h-2zM4 2h16v2H4zm0 12h4v2H4zm4 2h8v2H8zm8-2h4v2h-4z"],
  key: [
    "M11 18H3v-2h8zm12-3h-2v3h-4v-2h2v-3h2v-2H11V8h2v1h10zM3 16H1V8h2zm14 0h-2v-1h-2v1h-2v-3h6zm-8-2H5v-4h4zm2-6H3V6h8z",
  ],
  languages: [
    "M7 2h4v2H7zM2 5h14v2H2zm9 2h2v2h-2zM9 9h2v2H9zm-2 2h2v2H7zM5 9h2v2H5zm4 4h2v2H9zm-4 0h2v2H5zm8 2h2v7h-2zm2-2h5v2h-5zm5 2h2v7h-2zm-5 2h5v2h-5z",
  ],
  "layout-sidebar-left": ["M2 5h20v14H2zm2 2v10h2V7zm4 0v10h12V7z"],
  "letter-b": ["M16 5H8v6h8v2H8v6h8v2H6V3h10zm2 14h-2v-6h2zm0-8h-2V5h2z"],
  "letter-i": ["M16 5h-3v14h3v2H8v-2h3V5H8V3h8z"],
  "letter-s": ["M16 21H7v-2h9zm2-2h-2v-6h2zm-2-6H8v-2h8zm-8-2H6V5h2zm9-6H8V3h9z"],
  "letter-u": ["M16 21H8v-2h8zm-8-2H6V3h2zm10 0h-2V3h2z"],
  link: ["M4 6h7v2H4zm0 10h7v2H4zM2 8h2v8H2zm18-2h-7v2h7zm0 10h-7v2h7zm2-8h-2v8h2zM7 11h10v2H7z"],
  "link-sharp": ["M2 6h9v2H2zm0 10h9v2H2zm0-8h2v8H2zm20-2h-9v2h9zm0 10h-9v2h9zm0-8h-2v8h2zM7 11h10v2H7z"],
  list: ["M6 6H4v2h2zm14 0H8v2h12zM4 11h2v2H4zm16 0H8v2h12zM4 16h2v2H4zm16 0H8v2h12z"],
  loader: [
    "M13 22h-2v-6h2zm-6-3H5v-2h2zm12 0h-2v-2h2zM9 17H7v-2h2zm8 0h-2v-2h2zm-9-4H2v-2h6zm14 0h-6v-2h6zM9 9H7V7h2zm8 0h-2V7h2zm-4-1h-2V2h2zM7 7H5V5h2zm12 0h-2V5h2z",
  ],
  loading: ["M4 12H2V6h2zm2-6H4V4h2zm6-2H6V2h6z"],
  "loading-3": [
    "M18 22H6v-2h12zM6 20H4v-2h2zm14 0h-2v-2h2zM4 18H2v-6h2zm18 0h-2V6h2zM9 14.998h2v2H9V15H7V9h2zm6-6h2v2h-2V9H9V7h6zM20 6h-2V4h2zm-2-2h-6V2h6z",
  ],
  lock: ["M5 8h14v2H5zm0 12h14v2H5zM3 10h2v10H3zm16 0h2v10h-2zM7 4h2v4H7zm2-2h6v2H9zm6 2h2v4h-2z"],
  "lock-open": ["M15 2H9v2H7v2h2V4h6v4H4v14h16V8h-3V4h-2zm0 8h3v10H6V10zm-2 3h-2v4h2z"],
  "lock-sharp": ["M3 8h18v2H3zm0 12h18v2H3zm0-10h2v10H3zm16 0h2v10h-2zM7 4h2v4H7zm0-2h10v2H7zm8 2h2v4h-2z"],
  logout: [
    "M8 11h12v2H8zm8-2h2v2h-2z",
    "M14 7h2v10h-2zm2 6h2v2h-2zM6 2h12v2H6zm0 18h12v2H6zM4 4h2v16H4zm14 0h2v3h-2zm0 13h2v3h-2z",
  ],
  megaphone: [
    "M4 6h12v2H4zM2 8h2v6H2zm2 6h12v2H4zM20 2h2v18h-2zm-2 16h2v2h-2zm-2-2h2v2h-2zm0-12h2v2h-2zm2-2h2v2h-2zM8 8h2v6H8zm-2 8h2v4H6zm2 4h4v2H8zm2-4h2v4h-2z",
  ],
  "message-text": ["M20 2H4v2h16zm0 14H6v2h14zm2-12h-2v12h2zM4 4H2v18h2zm2 14H4v2h2zm0-6h4v2H6zm0-4h8v2H6z"],
  minus: ["M4 11h16v2H4z"],
  "minus-box": ["M4 2h16v2H4zm0 18h16v2H4zM2 4h2v16H2zm18 0h2v16h-2zM8 11h8v2H8z"],
  moon: [
    "M18 22H8v-2h10zM8 20H6v-2h2zm12 0h-2v-2h2zM6 18H4v-2h2zm16 0h-2v-4h-2v-2h2v-2h2zM4 16H2V6h2zm14 0h-6v-2h6zm-6-2h-2v-2h2zm-2-2H8V6h2zM6 6H4V4h2zm8-2h-2v2h-2V4H6V2h8z",
  ],
  "more-horizontal": [
    "M3 9h2v2H3zm8 0h2v2h-2zm8 0h2v2h-2zM1 11h2v2H1zm8 0h2v2H9zm8 0h2v2h-2zM3 13h2v2H3zm8 0h2v2h-2zm8 0h2v2h-2zM5 11h2v2H5zm8 0h2v2h-2zm8 0h2v2h-2z",
  ],
  package: [
    "M10 20h4v2h-4zm0-16h4V2h-4zm0 6h4v2h-4zm4 8h4v2h-4zm0-12h4V4h-4zm0 2h4v2h-4zm4 8h4v2h-4zm0-8h4V6h-4zM6 18h4v2H6zM6 6h4V4H6zm0 2h4v2H6zm-4 8h4v2H2zm0-8h4V6H2z",
    "M2 6h2v12H2zm18 0h2v12h-2zm-8 6h2v8h-2zm-2-6h4v2h-4z",
  ],
  "paint-bucket": ["M8 3h8v2H8zm0 2H6v4H4v12h16V9h-2V5h-2v4H8zm8 6h2v8H6v-8h2v6h2v-4h2v2h2v-2h2z"],
  pencil: [
    "M4 16h2v2h2v2h2v2H2v-8h2zm8 4h-2v-2h2zm2-2h-2v-2h2zm-4-2H8v-2h2zm6 0h-2v-2h2zM6 14H4v-2h2zm6 0h-2v-2h2zm6 0h-2v-2h2zM8 12H6v-2h2zm6 0h-2v-2h2zm6 0h-2v-2h2zm-10-2H8V8h2zm8 0h-2V8h2zm4 0h-2V8h2zM12 8h-2V6h2zm4 0h-2V6h2zm4 0h-2V6h2zm-6-2h-2V4h2zm4 0h-2V4h2zm-2-2h-2V2h2z",
  ],
  play: ["M15 11h-2V9h2zm0 4h-2v-2h2zm-2 2h-2v-2h2zm0-8h-2V7h2zm-2-2H9V5h2zM9 21H7V3h2zm6-8h2v-2h-2zm-6 4h2v2H9z"],
  plug: ["M16 18h-3v4h-2v-4H8v-2h8zm-8-2H6v-2h2zm10 0h-2v-2h2zm-8-9h4V2h2v5h5v2h-1v5h-2V9H6v5H4V9H3V7h5V2h2z"],
  "plug-solid": ["M10 7h4V2h2v5h5v2h-1v5h-2v2h-2v2h-3v4h-2v-4H8v-2H6v-2H4V9H3V7h5V2h2z"],
  plus: ["M13 11h7v2h-7v7h-2v-7H4v-2h7V4h2z"],
  pointer: [
    "M17 9h2v3h-2zm-4-2h2v4h-2zM9 3h2v8H9zM5 3h2v10H5zm14 6h2v2h-2zm-4-2h2v2h-2zm-4 0h2v2h-2zM7 1h2v2H7zM3 11h2v2H3zm-2 2h2v2H1zm0 2h2v2H1zm2 2h2v2H3zm2 2h2v2H5zm2 2h12v2H7zm12-2h2v2h-2zm2-8h2v8h-2zM5 13h2v2H5zm2 2h2v2H7z",
  ],
  power: [
    "M6 20h12v2H6zM18 6h2v2h-2zM4 6h2v2H4zm2-2h2v2H6zm10 0h2v2h-2zM4 18h2v2H4zm14 0h2v2h-2zM2 8h2v10H2zm18 0h2v10h-2zm-9-6h2v9h-2z",
  ],
  redo: ["M20 8H6v2h14zM4 10h2v8H4zm2 8h6v2H6z", "M18 6h-2v6h2zm-2-2h-2v8h2zm0 8h-2v2h2z"],
  refresh: [
    "M13 20H9v-2h4zm6-4h2v2h-2v2h-2v-2h-2v-2h2V8h2zM9 18H7v-2h2zM7 6h2v2H7v8H5V8H3V6h2V4h2zm8 10h-2v-2h2zm8 0h-2v-2h2zM3 10H1V8h2zm8 0H9V8h2zm6-2h-2V6h2zm-2-2h-4V4h4z",
  ],
  reload: [
    "M16 4h2v6h-2zm-2-2h2v2h-2zm0 2h2v8h-2zM4 8H2v5h2z",
    "M4 6h16v2H4zm4 14H6v-6h2zm2 2H8v-2h2zm0-2H8v-8h2zm10-4h2v-5h-2z",
    "M20 18H4v-2h16z",
  ],
  repeat: [
    "M17 5h2v2h-2zM5 17h2v2H5zm6-14h2v6h-2zM9 1h2v8H9zm0 8h2v2H9zm10 8H9v2h10zM5 7H3v10h2z",
    "M13 15h-2v6h2zm2-2h-2v8h2zm0 8h-2v2h2zM5 5h10v2H5zm14 12h2V7h-2z",
  ],
  "road-sign": [
    "M2 10h2v2H2zm0 4h2v-2H2zm20-4h-2v2h2zm0 4h-2v-2h2zM4 8h2v2H4zm0 8h2v-2H4zm16-8h-2v2h2zm0 8h-2v-2h2zM6 6h2v2H6zm0 12h2v-2H6zM18 6h-2v2h2zm0 12h-2v-2h2zM8 4h2v2H8zm0 16h2v-2H8zm8-16h-2v2h2zm0 16h-2v-2h2zM10 2h2v2h-2zm0 20h2v-2h-2zm4-20h-2v2h2zm0 20h-2v-2h2zm2-11h2v2h-2zm-2-2h2v6h-2zm-2-2h2v10h-2zm-4 4h2v4H8z",
    "M10 11h3v2h-3z",
  ],
  robot: [
    "M5 7h14v2H5zm0 12h14v2H5zM3 9h2v10H3zm16 0h2v10h-2zM1 13h2v2H1zm20 0h2v2h-2zM11 5h2v2h-2zM7 3h4v2H7zm1 9h2v4H8zm6 0h2v4h-2z",
  ],
  "scan-barcode": [
    "M16 2h4v2h-4zm4 2h2v4h-2zm0 12h2v4h-2zm-4 4h4v2h-4zM4 20h4v2H4zm-2-4h2v4H2zM2 4h2v4H2zm2-2h4v2H4zm3 6h2v8H7zm4 0h2v8h-2zm5 0h2v8h-2z",
  ],
  search: [
    "M22 22h-2v-2h2zm-2-2h-2v-2h2zm-6-2H6v-2h8zm4 0h-2v-2h2zM6 16H4v-2h2zm10 0h-2v-2h2zM4 14H2V6h2zm14 0h-2V6h2zM6 6H4V4h2zm10 0h-2V4h2zm-2-2H6V2h8z",
  ],
  send: [
    "M4 19h4v2H2v-8h2zm8 0H8v-2h4zm4-2h-4v-2h4zm4-2h-4v-2h4zm-10-2H4v-2h6zm12 0h-2v-2h2zM8 5H4v6H2V3h6zm12 6h-4V9h4zm-4-2h-4V7h4zm-4-2H8V5h4z",
  ],
  server: ["M6 7h4v2H6zm0 8h4v2H6zM2 5h2v14H2zm18 0h2v14h-2zM4 19h16v2H4zM4 3h16v2H4zm0 8h16v2H4z"],
  "settings-cog": [
    "M4 20h3v-2h4v4h2v-4h4v2h-2v4H9v-4H7v2H2v-5h2zm18 2h-5v-2h3v-3h2zM6 11H2v2h4v4H4v-2H0V9h4V7h2zm14-2h4v6h-4v2h-2v-4h4v-2h-4V7h2zm-6 7h-4v-2h4zm-4-2H8v-4h2zm6 0h-2v-4h2zm-2-4h-4V8h4zM7 4H4v3H2V2h5zm8 0h2V2h5v5h-2V4h-3v2h-4V2h-2v4H7V4h2V0h6z",
  ],
  shield: [
    "M4 2h16v2H4zM2 4h2v10H2zm18 0h2v10h-2zM4 14h2v2H4zm2 2h2v2H6zm4 4h4v2h-4zm10-6h-2v2h2zm-2 2h-2v2h2zm-2 2h-2v2h2zm-6 0H8v2h2z",
  ],
  "shield-off": [
    "M8 2h14v12h-2V4H8zM2 8h2v6H2zm2 6h2v2H4zm4 2H6v2h2v2h2v2h4v-2h-4v-2H8zm10 0h-2v2h2v2h2v2h2v-2h-2v-2h-2zM4 2H2v2h2v2h2v2h2v2h2v2h2v2h2v2h2v-2h-2v-2h-2v-2h-2V8H8V6H6V4H4z",
  ],
  ship: [
    "M14 8h2v2h-2zm4 8h2v2h-2zM8 4h2v4H8z",
    "M6 6h8v2H6zm-4 4h20v2H2zm18 2h2v4h-2zM2 12h2v6H2zm4-4h2v2H6z",
    "M0 16h4v2H0zm4 2h4v2H4zm4-2h4v2H8zm4 2h4v2h-4zm4-2h4v2h-4zm4 2h4v2h-4z",
  ],
  shuffle: ["M10 19H2v-2h8zm12 0h-8v-2h8zm-10-2h-2v-6h2zm6-10h2v2h2v2h-2v2h-2v2h-2v-4h-4V9h4V5h2zM8 11H2V9h6z"],
  "sliders-horizontal": [
    "M17 18h5v2h-5v2h-2v-6h2zm-4 2H2v-2h11zm-4-5H7v-2H2v-2h5V9h2zm13-2H11v-2h11zm-7-9h7v2h-7v2h-2V2h2zm-4 2H2V4h9z",
  ],
  smile: [
    "M6 20h12v2H6zM6 2h12v2H6zm12 2h2v2h-2zM4 4h2v2H4zm0 14h2v2H4zm14 0h2v2h-2zM2 6h2v12H2zm18 0h2v12h-2zM7 13h2v2H7zm2 2h6v2H9zm6-2h2v2h-2zM8 8h2v2H8zm6 0h2v2h-2z",
  ],
  "sort-horizontal": [
    "M20 16v2H4v-2zm-10-2v2H6v-2zm0-2v2H8v-2zm0 6v2H6v-2zm0 2v2H8v-2zM4 6v2h16V6zm10-2v2h4V4zm0-2v2h2V2zm0 6v2h4V8zm0 2v2h2v-2z",
  ],
  sparkle: [
    "M11 1h2v4h-2zm0 22h2v-4h-2zM9 5h2v4H9zm0 14h2v-4H9zm4-14h2v4h-2zm0 14h2v-4h-2zM5 9h4v2H5zm14 0h-4v2h4zM1 11h4v2H1zm22 0h-4v2h4zM5 13h4v2H5zm14 0h-4v2h4z",
  ],
  stop: ["M20 20H4V4h16zM6 18h12V6H6zm8-4h-4v-4h4z"],
  sword: ["M11 2h2v2h-2zM9 4h2v12H9zm4 0h2v12h-2zM7 16h10v2H7zm4 2h2v4h-2z"],
  terminal: ["M4 2h16v2H4zm0 18h16v2H4zM2 4h2v16H2zm18 0h2v16h-2zM6 16h2v2H6zm2-2h2v2H8zm-2-2h2v2H6z"],
  "text-cursor-input": [
    "M5 3h4v2H5zm4 2h2v14H9zm2-2h4v2h-4zM4 7h3v2H4zM2 9h2v6H2zm2 6h3v2H4zm9 0h7v2h-7zm7-6h2v6h-2zm-7-2h7v2h-7zM6 19h3v2H6zm5 0h3v2h-3z",
  ],
  "text-search": ["M20 4H4v2h16zm0 4H4v2h16zm-8 4H4v2h8zm8 0h-6v6h6v2h2v-2h-2zm-4 4v-2h2v2zm-4 0H4v2h8z"],
  "text-start-a": ["M12 6h10v2H12zm0 4h10v2H12zM2 14h20v2H2zm0 4h20v2H2zM2 6h2v6H2zm6 0h2v6H8zM4 4h4v2H4zm0 4h4v2H4z"],
  "text-start-t": [
    "M12 6h10v2H12zm0 4h10v2H12zM2 14h20v2H2zm0 4h20v2H2zM2 4h8v2H2zm3 2h2v6H5z",
    "M3 10h6v2H3zM2 6h2v2H2zm6 0h2v2H8z",
  ],
  tools: [
    "M9 22H7v-2h2zm12-6h2v6h-6v-6h2v-6h2zm-2 2v2h2v-2zM7 20H5v-8h2zm4 0H9v-8h2zm-6-8H3v-2h2zm8 0h-2v-2h2zM3 10H1V4h2zm12 0h-2V4h2zm4 0h-2V4h2zm4 0h-2V4h2zM7 6h2V2h4v2h-2v4H5V4H3V2h4zm14-2h-2V2h2z",
  ],
  trash: ["M18 22H6v-2h12zM9 6h6V4h2v2h5v2h-2v12h-2V8H6v12H4V8H2V6h5V4h2zm6-2H9V2h6z"],
  trophy: ["M16 17h-3v2h2v2H9v-2h2v-2H8v-2h8zm2-12h4v6h-2V7h-2v4h2v2h-2v2h-2V5H8v10H6v-2H4v-2h2V7H4v4H2V5h4V3h12z"],
  undo: ["M18 20h-6v-2h6zm2-2h-2v-8h2zm-10-4H8v-2H6v-2H4V8h2V6h2V4h2v4h8v2h-8z"],
  university: [
    "M1 10h2v10H1zm2 10h18v2H3zm18-10h2v10h-2zM3 8h4v2H3zm14 0h4v2h-4zM7 6h2v2H7zm2-2h2v2H9zm2-2h2v2h-2zm2 2h2v2h-2zm2 2h2v2h-2zm-4 2h2v2h-2zm-2 2h2v2H9zm2 2h2v2h-2zm2-2h2v2h-2zm-8 3h2v2H5zm0 4h2v2H5zm12 0h2v2h-2zm0-4h2v2h-2zm-8 5h2v2H9zm0-2h6v2H9zm4 2h2v2h-2z",
  ],
  unlink: ["M4 6h5v2H4zm11 0h5v2h-5zm0 10h5v2h-5zM4 16h5v2H4zm16-8h2v8h-2zM2 8h2v8H2zm9-4h2v16h-2z"],
  upload: ["M19 21H5v-2h14zM5 19H3v-4h2zm16 0h-2v-4h2zM13 5h2v2h2v2h-4v8h-2V9H7V7h2V5h2V3h2z"],
  user: ["M9 2h6v2H9zm0 8h6v2H9zm6-6h2v6h-2zM7 4h2v6H7zM4 18h2v4H4zm14 0h2v4h-2zM8 14h8v2H8zm-2 2h2v2H6zm10 0h2v2h-2z"],
  users: [
    "M5 2h6v2H5zm10 0h4v2h-4zM5 10h6v2H5zm10 0h4v2h-4zm4-6h2v6h-2zm-8 0h2v6h-2zM3 4h2v6H3zM0 18h2v4H0zm14 0h2v4h-2zm8 0h2v4h-2zM4 14h8v2H4zm12 0h4v2h-4zM2 16h2v2H2zm10 0h2v2h-2zm8 0h2v2h-2z",
  ],
  "volume-3": [
    "M11 22H9v-2H7v-2h2V6H7V4h2V2h2zm8 0h-6v-2h6zm2-2h-2v-2h2zM7 18H5v-2h2zm10 0h-4v-2h4zm6 0h-2V6h2zM5 10H3v4h2v2H1V8h4zm14 6h-2V8h2zm-4-2h-2v-4h2zM7 8H5V6h2zm10 0h-4V6h4zm4-2h-2V4h2zm-2-2h-6V2h6z",
  ],
  "warning-diamond": [
    "M2 10h2v2H2zm0 4h2v-2H2zm20-4h-2v2h2zm0 4h-2v-2h2zM4 8h2v2H4zm0 8h2v-2H4zm16-8h-2v2h2zm0 8h-2v-2h2zM6 6h2v2H6zm0 12h2v-2H6zM18 6h-2v2h2zm0 12h-2v-2h2zM8 4h2v2H8zm0 16h2v-2H8zm8-16h-2v2h2zm0 16h-2v-2h2zM10 2h2v2h-2zm0 20h2v-2h-2zm4-20h-2v2h2zm0 20h-2v-2h2zm-3-5h2v-2h-2zm0-4h2V7h-2z",
  ],
  zap: [
    "M4 13h8v6h2v2h-2v2h-2v-8H2v-4h2zm12 6h-2v-2h2zm2-2h-2v-2h2zm2-2h-2v-2h2zm-6-6h8v4h-2v-2h-8V5h-2V3h2V1h2zm-8 2H4V9h2zm2-2H6V7h2zm2-2H8V5h2z",
  ],
}

export type IconProps = SVGProps<SVGSVGElement> & {
  /** Width and height; one line of text by default, like a glyph. */
  size?: number | string
}

export type Icon = (props: IconProps) => JSX.Element

function pixel(name: IconName): Icon {
  const paths = PIXELARTICON_PATHS[PIXELARTICONS[name]]
  function PixelIcon({ size = "1em", ...props }: IconProps) {
    return (
      <svg
        xmlns="http://www.w3.org/2000/svg"
        viewBox="0 0 24 24"
        width={size}
        height={size}
        fill="currentColor"
        {...props}
      >
        {paths.map((d) => (
          <path key={d} d={d} />
        ))}
      </svg>
    )
  }
  PixelIcon.displayName = name
  return PixelIcon
}

export const ArchiveIcon = pixel("ArchiveIcon")
export const ArrowCircleUpIcon = pixel("ArrowCircleUpIcon")
export const ArrowClockwiseIcon = pixel("ArrowClockwiseIcon")
export const ArrowCounterClockwiseIcon = pixel("ArrowCounterClockwiseIcon")
export const ArrowDownIcon = pixel("ArrowDownIcon")
export const ArrowElbowDownLeftIcon = pixel("ArrowElbowDownLeftIcon")
export const ArrowLeftIcon = pixel("ArrowLeftIcon")
export const ArrowRightIcon = pixel("ArrowRightIcon")
export const ArrowSquareInIcon = pixel("ArrowSquareInIcon")
export const ArrowSquareOutIcon = pixel("ArrowSquareOutIcon")
export const ArrowUpIcon = pixel("ArrowUpIcon")
export const ArrowsClockwiseIcon = pixel("ArrowsClockwiseIcon")
export const ArrowsCounterClockwiseIcon = pixel("ArrowsCounterClockwiseIcon")
export const ArrowsLeftRightIcon = pixel("ArrowsLeftRightIcon")
export const ArrowsSplitIcon = pixel("ArrowsSplitIcon")
export const BankIcon = pixel("BankIcon")
export const BarricadeIcon = pixel("BarricadeIcon")
export const BedIcon = pixel("BedIcon")
export const BellIcon = pixel("BellIcon")
export const BellSlashIcon = pixel("BellSlashIcon")
export const BookOpenTextIcon = pixel("BookOpenTextIcon")
export const BoxArrowUpIcon = pixel("BoxArrowUpIcon")
export const BracketsCurlyIcon = pixel("BracketsCurlyIcon")
export const CalendarDotIcon = pixel("CalendarDotIcon")
export const CalendarIcon = pixel("CalendarIcon")
export const CalendarPlusIcon = pixel("CalendarPlusIcon")
export const CalendarXIcon = pixel("CalendarXIcon")
export const CaretDownIcon = pixel("CaretDownIcon")
export const CaretLeftIcon = pixel("CaretLeftIcon")
export const CaretRightIcon = pixel("CaretRightIcon")
export const CaretUpIcon = pixel("CaretUpIcon")
export const ChartBarIcon = pixel("ChartBarIcon")
export const ChatTextIcon = pixel("ChatTextIcon")
export const CheckCircleIcon = pixel("CheckCircleIcon")
export const CheckIcon = pixel("CheckIcon")
export const CircleDashedIcon = pixel("CircleDashedIcon")
export const CircleIcon = pixel("CircleIcon")
export const CircleNotchIcon = pixel("CircleNotchIcon")
export const ClockCountdownIcon = pixel("ClockCountdownIcon")
export const ClockCounterClockwiseIcon = pixel("ClockCounterClockwiseIcon")
export const ClockIcon = pixel("ClockIcon")
export const CodeIcon = pixel("CodeIcon")
export const CompassIcon = pixel("CompassIcon")
export const CopyIcon = pixel("CopyIcon")
export const CreditCardIcon = pixel("CreditCardIcon")
export const CrownCrossIcon = pixel("CrownCrossIcon")
export const CrownIcon = pixel("CrownIcon")
export const CubeIcon = pixel("CubeIcon")
export const CursorClickIcon = pixel("CursorClickIcon")
export const DatabaseIcon = pixel("DatabaseIcon")
export const DoorIcon = pixel("DoorIcon")
export const DotsThreeIcon = pixel("DotsThreeIcon")
export const DownloadIcon = pixel("DownloadIcon")
export const DownloadSimpleIcon = pixel("DownloadSimpleIcon")
export const ElevatorIcon = pixel("ElevatorIcon")
export const EraserIcon = pixel("EraserIcon")
export const EyeIcon = pixel("EyeIcon")
export const EyeSlashIcon = pixel("EyeSlashIcon")
export const FingerprintIcon = pixel("FingerprintIcon")
export const FlagBannerIcon = pixel("FlagBannerIcon")
export const FlagCheckeredIcon = pixel("FlagCheckeredIcon")
export const FolderIcon = pixel("FolderIcon")
export const FunnelSimpleIcon = pixel("FunnelSimpleIcon")
export const GameControllerIcon = pixel("GameControllerIcon")
export const GearIcon = pixel("GearIcon")
export const GearSixIcon = pixel("GearSixIcon")
export const GlobeSimpleIcon = pixel("GlobeSimpleIcon")
export const HandCoinsIcon = pixel("HandCoinsIcon")
export const HandIcon = pixel("HandIcon")
export const HardDrivesIcon = pixel("HardDrivesIcon")
export const HashIcon = pixel("HashIcon")
export const HashStraightIcon = pixel("HashStraightIcon")
export const HeartIcon = pixel("HeartIcon")
export const HourglassIcon = pixel("HourglassIcon")
export const InfoIcon = pixel("InfoIcon")
export const KeyIcon = pixel("KeyIcon")
export const LightningIcon = pixel("LightningIcon")
export const LinkBreakIcon = pixel("LinkBreakIcon")
export const LinkIcon = pixel("LinkIcon")
export const LinkSimpleIcon = pixel("LinkSimpleIcon")
export const ListIcon = pixel("ListIcon")
export const ListMagnifyingGlassIcon = pixel("ListMagnifyingGlassIcon")
export const LockIcon = pixel("LockIcon")
export const LockOpenIcon = pixel("LockOpenIcon")
export const LockSimpleIcon = pixel("LockSimpleIcon")
export const MagnifyingGlassIcon = pixel("MagnifyingGlassIcon")
export const MegaphoneIcon = pixel("MegaphoneIcon")
export const MinusIcon = pixel("MinusIcon")
export const MoonIcon = pixel("MoonIcon")
export const NetworkIcon = pixel("NetworkIcon")
export const PackageIcon = pixel("PackageIcon")
export const PaletteIcon = pixel("PaletteIcon")
export const PaperPlaneTiltIcon = pixel("PaperPlaneTiltIcon")
export const PasswordIcon = pixel("PasswordIcon")
export const PencilIcon = pixel("PencilIcon")
export const PencilSimpleIcon = pixel("PencilSimpleIcon")
export const PlayIcon = pixel("PlayIcon")
export const PlugIcon = pixel("PlugIcon")
export const PlugsIcon = pixel("PlugsIcon")
export const PlusIcon = pixel("PlusIcon")
export const PowerIcon = pixel("PowerIcon")
export const ProhibitIcon = pixel("ProhibitIcon")
export const ProhibitInsetIcon = pixel("ProhibitInsetIcon")
export const PulseIcon = pixel("PulseIcon")
export const PuzzlePieceIcon = pixel("PuzzlePieceIcon")
export const QuestionIcon = pixel("QuestionIcon")
export const RepeatIcon = pixel("RepeatIcon")
export const RobotIcon = pixel("RobotIcon")
export const RocketLaunchIcon = pixel("RocketLaunchIcon")
export const ShieldCheckIcon = pixel("ShieldCheckIcon")
export const ShieldSlashIcon = pixel("ShieldSlashIcon")
export const ShieldWarningIcon = pixel("ShieldWarningIcon")
export const ShuffleIcon = pixel("ShuffleIcon")
export const SidebarIcon = pixel("SidebarIcon")
export const SignOutIcon = pixel("SignOutIcon")
export const SignpostIcon = pixel("SignpostIcon")
export const SlidersHorizontalIcon = pixel("SlidersHorizontalIcon")
export const SmileyIcon = pixel("SmileyIcon")
export const SparkleIcon = pixel("SparkleIcon")
export const SpeakerHighIcon = pixel("SpeakerHighIcon")
export const SpinnerBallIcon = pixel("SpinnerBallIcon")
export const SpinnerIcon = pixel("SpinnerIcon")
export const StopIcon = pixel("StopIcon")
export const SwapIcon = pixel("SwapIcon")
export const SwordIcon = pixel("SwordIcon")
export const TargetIcon = pixel("TargetIcon")
export const TerminalWindowIcon = pixel("TerminalWindowIcon")
export const TextAaIcon = pixel("TextAaIcon")
export const TextBIcon = pixel("TextBIcon")
export const TextItalicIcon = pixel("TextItalicIcon")
export const TextStrikethroughIcon = pixel("TextStrikethroughIcon")
export const TextTSlashIcon = pixel("TextTSlashIcon")
export const TextUnderlineIcon = pixel("TextUnderlineIcon")
export const TimerIcon = pixel("TimerIcon")
export const TranslateIcon = pixel("TranslateIcon")
export const TrashIcon = pixel("TrashIcon")
export const TrayIcon = pixel("TrayIcon")
export const TrophyIcon = pixel("TrophyIcon")
export const UploadSimpleIcon = pixel("UploadSimpleIcon")
export const UserIcon = pixel("UserIcon")
export const UsersIcon = pixel("UsersIcon")
export const WarningIcon = pixel("WarningIcon")
export const WrenchIcon = pixel("WrenchIcon")
export const XCircleIcon = pixel("XCircleIcon")
export const XIcon = pixel("XIcon")
