/** The values each of Steward's texts takes; written by `./gradlew :steward:generateApiTypes`. */
import type { Arg } from "@/lib/texts"

export type TextArgs = {
  "alert.claimed": {
    payment: Arg["text"]
    amount: Arg["money"]
    reference: Arg["text"]
  }
  "alert.clear": {
    subject: Arg["text"]
  }
  "alert.disk": {
    percent: Arg["number"]
  }
  "alert.dm": Record<string, never>
  "alert.docker-state": {
    state: Arg["text"]
  }
  "alert.dump-matters": Record<string, never>
  "alert.failed-for": {
    person: Arg["mention"]
    error: Arg["text"]
  }
  "alert.failed-in": {
    channel: Arg["text"]
    error: Arg["text"]
  }
  "alert.health-fails": Record<string, never>
  "alert.level": {
    level: Arg["choice"]
  }
  "alert.link-failed": Record<string, never>
  "alert.link-refused": Record<string, never>
  "alert.lock-not-kept": {
    count: Arg["number"]
  }
  "alert.lock-without-channel": Record<string, never>
  "alert.memory": {
    percent: Arg["number"]
  }
  "alert.no-archive": Record<string, never>
  "alert.no-backup": Record<string, never>
  "alert.no-dump": Record<string, never>
  "alert.no-limit": Record<string, never>
  "alert.no-offsite": Record<string, never>
  "alert.no-services": Record<string, never>
  "alert.no-tier": {
    payment: Arg["text"]
    amount: Arg["money"]
    reference: Arg["text"]
  }
  "alert.nobody-locked": Record<string, never>
  "alert.none-adopted": Record<string, never>
  "alert.not-compared": Record<string, never>
  "alert.not-open": {
    payment: Arg["text"]
    amount: Arg["money"]
    reference: Arg["text"]
    status: Arg["choice"]
  }
  "alert.not-running": {
    service: Arg["text"]
  }
  "alert.old-archive": {
    volume: Arg["text"]
    hours: Arg["number"]
  }
  "alert.old-dump": {
    hours: Arg["number"]
  }
  "alert.old-offsite": {
    hours: Arg["number"]
  }
  "alert.older-image": {
    services: Arg["list"]
    count: Arg["number"]
  }
  "alert.only-started": Record<string, never>
  "alert.payment": Record<string, never>
  "alert.permitted-age": {
    hours: Arg["number"]
  }
  "alert.purchase-failed": Record<string, never>
  "alert.refused": {
    reference: Arg["text"]
    error: Arg["text"]
  }
  "alert.role-ambiguous": {
    name: Arg["text"]
  }
  "alert.role-not-changed": {
    role: Arg["choice"]
    given: Arg["choice"]
  }
  "alert.role-not-created": {
    name: Arg["text"]
  }
  "alert.run": {
    run: Arg["number"]
  }
  "alert.run-failed": {
    kind: Arg["choice"]
  }
  "alert.several": {
    subjects: Arg["list"]
    count: Arg["number"]
  }
  "alert.threshold": {
    percent: Arg["number"]
  }
  "alert.to": {
    person: Arg["mention"]
  }
  "alert.unhealthy": {
    service: Arg["text"]
  }
  "alert.unknown-reference": {
    payment: Arg["text"]
    amount: Arg["money"]
    reference: Arg["text"]
  }
  "alert.words": {
    text: Arg["text"]
  }
  "check.syntax.attribute-missing": {
    name: Arg["text"]
    at: Arg["number"]
  }
  "check.syntax.case-never-closed": {
    at: Arg["number"]
  }
  "check.syntax.case-not-opened": {
    case: Arg["text"]
    at: Arg["number"]
  }
  "check.syntax.case-twice": {
    name: Arg["text"]
    case: Arg["text"]
    at: Arg["number"]
  }
  "check.syntax.case-unnamed": {
    name: Arg["text"]
    at: Arg["number"]
  }
  "check.syntax.choice-in-argument": {
    at: Arg["number"]
  }
  "check.syntax.choice-never-closed": {
    name: Arg["text"]
    at: Arg["number"]
  }
  "check.syntax.closes-nothing": {
    at: Arg["number"]
  }
  "check.syntax.comma-after-name": {
    at: Arg["number"]
  }
  "check.syntax.comma-before-cases": {
    at: Arg["number"]
  }
  "check.syntax.kind-missing": {
    name: Arg["text"]
    at: Arg["number"]
  }
  "check.syntax.opens-nothing": {
    at: Arg["number"]
  }
  "check.syntax.other-missing": {
    name: Arg["text"]
    at: Arg["number"]
  }
  "check.syntax.style-missing": {
    name: Arg["text"]
    kind: Arg["text"]
    at: Arg["number"]
  }
  "check.syntax.value-not-closed": {
    name: Arg["text"]
    at: Arg["number"]
  }
  "check.tag.closes-nothing": {
    tag: Arg["text"]
  }
  "check.tag.closes-other": {
    tag: Arg["text"]
    open: Arg["text"]
  }
  "check.tag.never-closed": {
    tag: Arg["text"]
  }
  "check.tag.packaged-colour": {
    tag: Arg["text"]
    tones: Arg["list"]
  }
  "check.tag.unknown": {
    tag: Arg["text"]
  }
  "check.tag.unknown-action": {
    action: Arg["text"]
    actions: Arg["list"]
  }
  "check.tag.unquoted-value": {
    name: Arg["text"]
    tag: Arg["text"]
  }
  "check.tag.value-in-argument": {
    name: Arg["text"]
    tag: Arg["text"]
  }
  "check.too-long": {
    length: Arg["number"]
    limit: Arg["number"]
  }
  "check.value.no-kind": {
    name: Arg["text"]
    kind: Arg["text"]
    kinds: Arg["list"]
  }
  "check.value.no-style": {
    name: Arg["text"]
    style: Arg["text"]
    kind: Arg["text"]
    styles: Arg["list"]
  }
  "check.value.other-kind": {
    name: Arg["text"]
    kind: Arg["text"]
    written: Arg["text"]
  }
  "check.value.plural-case": {
    name: Arg["text"]
    case: Arg["text"]
    cases: Arg["list"]
  }
  "check.value.plural-on": {
    name: Arg["text"]
    kind: Arg["text"]
  }
  "check.value.select-on": {
    name: Arg["text"]
    kind: Arg["text"]
  }
  "check.value.styleless": {
    name: Arg["text"]
    style: Arg["text"]
    kind: Arg["text"]
  }
  "check.value.unknown": {
    name: Arg["text"]
    offered: Arg["list"]
  }
  "check.value.unshown": {
    role: Arg["text"]
  }
  "journal.action": {
    action: Arg["choice"]
  }
  "journal.actor": {
    kind: Arg["choice"]
  }
  "journal.add-plugin": {
    service: Arg["text"]
    artifact: Arg["text"]
  }
  "journal.admin-root": Record<string, never>
  "journal.announce": {
    languages: Arg["list"]
  }
  "journal.by": Record<string, never>
  "journal.cancel-run": {
    run: Arg["number"]
    kind: Arg["choice"]
  }
  "journal.clear-launch": Record<string, never>
  "journal.clear-smp-start": Record<string, never>
  "journal.complete-objective": {
    objective: Arg["text"]
  }
  "journal.concerns": Record<string, never>
  "journal.console": {
    service: Arg["text"]
    command: Arg["text"]
  }
  "journal.download-backup": {
    archive: Arg["text"]
    size: Arg["message"]
  }
  "journal.download-backup-unsized": {
    archive: Arg["text"]
  }
  "journal.enforce-pack": Record<string, never>
  "journal.exempt-pack": Record<string, never>
  "journal.forget-factors": {
    keys: Arg["number"]
    sessions: Arg["number"]
  }
  "journal.grant-access": {
    days: Arg["number"]
    until: Arg["instant"]
  }
  "journal.grant-admin": Record<string, never>
  "journal.held-key": {
    label: Arg["text"]
    unlocked: Arg["choice"]
  }
  "journal.link": Record<string, never>
  "journal.minecraft": Record<string, never>
  "journal.person": {
    person: Arg["mention"]
  }
  "journal.register-key": {
    label: Arg["text"]
  }
  "journal.remove-key": {
    label: Arg["text"]
    left: Arg["number"]
  }
  "journal.rename-key": {
    label: Arg["text"]
  }
  "journal.revoke-access": {
    grants: Arg["number"]
  }
  "journal.revoke-admin": {
    below: Arg["list"]
    count: Arg["number"]
  }
  "journal.save-messages": {
    bundle: Arg["text"]
  }
  "journal.save-settings": {
    service: Arg["text"]
    group: Arg["text"]
  }
  "journal.set-alert-preference": {
    alert: Arg["choice"]
    channel: Arg["choice"]
    enabled: Arg["choice"]
  }
  "journal.set-launch": {
    to: Arg["instant"]
  }
  "journal.set-phase": {
    from: Arg["choice"]
    to: Arg["choice"]
  }
  "journal.set-phase-because": {
    from: Arg["choice"]
    to: Arg["choice"]
    reason: Arg["text"]
  }
  "journal.set-playtime": {
    seconds: Arg["duration"]
  }
  "journal.set-smp-start": {
    to: Arg["instant"]
    movedGrants: Arg["number"]
  }
  "journal.settle": {
    reference: Arg["text"]
    matched: Arg["choice"]
    days: Arg["number"]
    ordered: Arg["number"]
    received: Arg["money"]
    donation: Arg["money"]
  }
  "journal.settle-by-hand": {
    reference: Arg["text"]
    days: Arg["number"]
    ordered: Arg["number"]
    donation: Arg["money"]
  }
  "journal.start-game": Record<string, never>
  "journal.unlink": {
    selfService: Arg["choice"]
  }
  "journal.unlock-milestone": {
    milestone: Arg["text"]
  }
  "journal.web-push-subscribe": Record<string, never>
  "journal.web-push-test": {
    alert: Arg["choice"]
  }
  "journal.web-push-unsubscribe": Record<string, never>
  "journal.written": {
    detail: Arg["text"]
  }
  "note.booked": {
    reference: Arg["text"]
    payer: Arg["mention"]
    days: Arg["number"]
    until: Arg["instant"]
  }
  "note.no-season-start": Record<string, never>
  "note.payment-booked": Record<string, never>
  "note.refused-for-the-hour": {
    person: Arg["mention"]
  }
  "note.runs-from-the-grant": {
    person: Arg["mention"]
    from: Arg["instant"]
  }
  "note.too-many-codes": Record<string, never>
  "report.agent-not-renewed": {
    reason: Arg["text"]
  }
  "report.backup-unread": {
    reason: Arg["text"]
  }
  "report.cancelled": Record<string, never>
  "report.down-refused": {
    services: Arg["list"]
  }
  "report.down-unnamed": Record<string, never>
  "report.evacuation-interrupted": {
    services: Arg["list"]
  }
  "report.failed-unexpectedly": {
    error: Arg["text"]
  }
  "report.fell-back": {
    outdated: Arg["choice"]
    reason: Arg["text"]
  }
  "report.fell-back-down": {
    outdated: Arg["choice"]
    reason: Arg["text"]
    minutes: Arg["number"]
    seen: Arg["message"]
  }
  "report.fell-back-healthy": {
    outdated: Arg["choice"]
    reason: Arg["text"]
  }
  "report.fell-back-interrupted": {
    outdated: Arg["choice"]
    reason: Arg["text"]
  }
  "report.foreign-newer": {
    services: Arg["list"]
    count: Arg["number"]
  }
  "report.foreign-not-recreated": {
    reason: Arg["text"]
  }
  "report.handed": {
    release: Arg["text"]
  }
  "report.held-back": {
    reason: Arg["message"]
    held: Arg["list"]
  }
  "report.held-down": {
    services: Arg["list"]
  }
  "report.held-left-out": {
    services: Arg["list"]
  }
  "report.held-not-remade": {
    services: Arg["list"]
  }
  "report.held-not-restarted": {
    services: Arg["list"]
  }
  "report.image-outdated": Record<string, never>
  "report.images-local": {
    services: Arg["list"]
    count: Arg["number"]
  }
  "report.images-uncompared": Record<string, never>
  "report.images-unread": {
    answer: Arg["text"]
  }
  "report.images-unverifiable": {
    services: Arg["list"]
    count: Arg["number"]
  }
  "report.made-again": {
    pull: Arg["choice"]
  }
  "report.no-backup-volumes": Record<string, never>
  "report.no-build": {
    artefact: Arg["text"]
    minecraft: Arg["text"]
    loader: Arg["text"]
  }
  "report.no-container": Record<string, never>
  "report.no-container-to-start": Record<string, never>
  "report.no-container-to-stop": Record<string, never>
  "report.no-database": Record<string, never>
  "report.no-offsite": Record<string, never>
  "report.no-source": {
    artefact: Arg["text"]
  }
  "report.no-standby": {
    run: Arg["choice"]
  }
  "report.not-fell-back": {
    outdated: Arg["choice"]
    reason: Arg["text"]
    answer: Arg["text"]
  }
  "report.not-healthy": {
    minutes: Arg["number"]
    seen: Arg["message"]
  }
  "report.not-in-release": {
    service: Arg["text"]
    reason: Arg["message"]
    installed: Arg["text"]
  }
  "report.not-installed": {
    artefact: Arg["text"]
    reason: Arg["text"]
  }
  "report.not-migrated": {
    reason: Arg["text"]
  }
  "report.not-mounted": {
    directory: Arg["text"]
  }
  "report.not-recreated": {
    outdated: Arg["choice"]
    reason: Arg["text"]
  }
  "report.not-restored": Record<string, never>
  "report.not-saved": Record<string, never>
  "report.not-stopped": {
    run: Arg["choice"]
    services: Arg["list"]
  }
  "report.nothing-changes": Record<string, never>
  "report.nothing-held": Record<string, never>
  "report.older-release": {
    release: Arg["text"]
    own: Arg["text"]
  }
  "report.one-shot-not-started": {
    release: Arg["text"]
    reason: Arg["text"]
  }
  "report.orphaned": Record<string, never>
  "report.pack-moves": {
    version: Arg["text"]
  }
  "report.pack-unchecked": {
    reason: Arg["message"]
  }
  "report.players-unknown": {
    services: Arg["list"]
    seconds: Arg["number"]
  }
  "report.plugin-removed": Record<string, never>
  "report.proxy-pack-unread": {
    error: Arg["text"]
  }
  "report.proxy-pack-without-sha-1": Record<string, never>
  "report.proxy-without-pack": Record<string, never>
  "report.pruned": {
    daily: Arg["number"]
    weekly: Arg["number"]
    monthly: Arg["number"]
    count: Arg["number"]
    archives: Arg["list"]
  }
  "report.recreating": {
    pull: Arg["choice"]
  }
  "report.release-unread": {
    repo: Arg["text"]
    error: Arg["text"]
  }
  "report.release-without-jar": {
    release: Arg["text"]
    artefact: Arg["text"]
  }
  "report.release-without-pack": {
    release: Arg["text"]
  }
  "report.release-without-sha-1": {
    release: Arg["text"]
    zip: Arg["text"]
  }
  "report.released-meanwhile": {
    release: Arg["text"]
    own: Arg["text"]
  }
  "report.remake-agent": Record<string, never>
  "report.remake-unknown": {
    services: Arg["list"]
  }
  "report.remake-unnamed": {
    kind: Arg["choice"]
  }
  "report.removal-empty": {
    artifact: Arg["text"]
  }
  "report.removal-failed": {
    reason: Arg["text"]
  }
  "report.removal-unknown": {
    service: Arg["text"]
    artifact: Arg["text"]
  }
  "report.removal-unnamed": Record<string, never>
  "report.removed": {
    files: Arg["list"]
  }
  "report.renewing-agent": Record<string, never>
  "report.restart-all-held": Record<string, never>
  "report.restart-none-in-scope": {
    scope: Arg["list"]
  }
  "report.restore-database-unsaved": {
    reason: Arg["text"]
  }
  "report.restore-failed": Record<string, never>
  "report.restore-not-a-volume": {
    volume: Arg["text"]
    archive: Arg["text"]
  }
  "report.restore-unknown": {
    archive: Arg["text"]
  }
  "report.restore-unnamed": Record<string, never>
  "report.restore-unsaved": {
    volume: Arg["text"]
  }
  "report.restored": {
    size: Arg["message"]
  }
  "report.restored-over": {
    dump: Arg["text"]
  }
  "report.runtime-unread": {
    answer: Arg["text"]
  }
  "report.saved": {
    size: Arg["message"]
    took: Arg["duration"]
  }
  "report.saving": Record<string, never>
  "report.sha-1-unread": {
    file: Arg["text"]
    error: Arg["text"]
  }
  "report.size": {
    amount: Arg["number"]
    unit: Arg["choice"]
  }
  "report.standby-no-container": {
    standby: Arg["text"]
  }
  "report.standby-not-started": {
    standby: Arg["text"]
    reason: Arg["text"]
  }
  "report.standby-not-stopped": {
    standby: Arg["text"]
    reason: Arg["text"]
  }
  "report.standby-seen": {
    standby: Arg["text"]
    seen: Arg["message"]
  }
  "report.standby-stopped": {
    standby: Arg["text"]
  }
  "report.standby-stopped-interrupted": {
    standby: Arg["text"]
    players: Arg["number"]
  }
  "report.standby-stopped-with-players": {
    standby: Arg["text"]
    players: Arg["number"]
    seconds: Arg["number"]
  }
  "report.standbys-interrupted": {
    standbys: Arg["list"]
  }
  "report.standbys-ready": {
    standbys: Arg["list"]
    run: Arg["choice"]
  }
  "report.standbys-unhealthy": {
    standbys: Arg["list"]
    minutes: Arg["number"]
  }
  "report.start-failed": {
    reason: Arg["text"]
  }
  "report.starts-again": Record<string, never>
  "report.stays-down": Record<string, never>
  "report.stop-failed": {
    reason: Arg["text"]
  }
  "report.stopped-for-restore": {
    archive: Arg["text"]
  }
  "report.stopped-while-saving": Record<string, never>
  "report.stopped-with-players": {
    players: Arg["number"]
    servers: Arg["list"]
    seconds: Arg["number"]
  }
  "report.unclaimed": {
    service: Arg["text"]
    file: Arg["text"]
  }
  "report.unmarked-archive": {
    services: Arg["list"]
  }
  "report.unverified-archive": {
    mark: Arg["text"]
  }
  "report.unverified-stop": {
    services: Arg["list"]
    run: Arg["choice"]
    failsTheRun: Arg["choice"]
  }
  "report.velocity-ahead": {
    version: Arg["text"]
    api: Arg["text"]
  }
  "report.wait-interrupted": Record<string, never>
  "report.words": {
    text: Arg["text"]
  }
  "run.duration": Record<string, never>
  "run.heading": Record<string, never>
  "run.kind": {
    kind: Arg["choice"]
  }
  "run.more": {
    count: Arg["number"]
  }
  "run.no-build": Record<string, never>
  "run.notes": Record<string, never>
  "run.services": Record<string, never>
  "run.stage": {
    stage: Arg["choice"]
  }
  "run.state": {
    state: Arg["choice"]
  }
  "run.status": {
    status: Arg["choice"]
  }
  "run.successful": {
    successful: Arg["number"]
    total: Arg["number"]
  }
  "run.took": {
    took: Arg["duration"]
  }
  "steward.alerts.all-clear": Record<string, never>
  "steward.alerts.none-raised": Record<string, never>
  "steward.alerts.now": Record<string, never>
  "steward.alerts.raised-by": {
    who: Arg["text"]
  }
  "steward.alerts.recent": Record<string, never>
  "steward.alerts.title": Record<string, never>
  "steward.alerts.unreadable": Record<string, never>
  "steward.announcements.ask": Record<string, never>
  "steward.announcements.compose": Record<string, never>
  "steward.announcements.host-channel": Record<string, never>
  "steward.announcements.no-channel": Record<string, never>
  "steward.announcements.send": Record<string, never>
  "steward.announcements.where": {
    languages: Arg["number"]
  }
  "steward.answer.agent-unconfigured": Record<string, never>
  "steward.answer.already-admin": Record<string, never>
  "steward.answer.answer-unreadable": Record<string, never>
  "steward.answer.backup-unfinished": {
    backup: Arg["text"]
  }
  "steward.answer.bot-token-refused": Record<string, never>
  "steward.answer.ceremony-elsewhere": {
    registration: Arg["choice"]
  }
  "steward.answer.ceremony-other-account": {
    registration: Arg["choice"]
  }
  "steward.answer.ceremony-stale": {
    registration: Arg["choice"]
  }
  "steward.answer.confirm-restore": {
    replaces: Arg["text"]
  }
  "steward.answer.discord-answered": {
    status: Arg["number"]
  }
  "steward.answer.discord-unreachable": {
    error: Arg["text"]
  }
  "steward.answer.empty": Record<string, never>
  "steward.answer.empty-text": {
    language: Arg["text"]
  }
  "steward.answer.enforced-already": Record<string, never>
  "steward.answer.exempt-already": Record<string, never>
  "steward.answer.grant-days": {
    most: Arg["number"]
  }
  "steward.answer.grants-per-hour": {
    count: Arg["number"]
  }
  "steward.answer.guild-unreadable": Record<string, never>
  "steward.answer.interrupted": Record<string, never>
  "steward.answer.keep-section": {
    path: Arg["text"]
    field: Arg["text"]
    value: Arg["text"]
  }
  "steward.answer.key-name": {
    most: Arg["number"]
  }
  "steward.answer.key-not-accepted": Record<string, never>
  "steward.answer.key-not-held": Record<string, never>
  "steward.answer.key-not-recent": {
    within: Arg["duration"]
  }
  "steward.answer.key-refused": {
    registration: Arg["choice"]
    why: Arg["text"]
  }
  "steward.answer.no-access-token": Record<string, never>
  "steward.answer.no-announcement": {
    announcement: Arg["number"]
  }
  "steward.answer.no-backup": {
    backup: Arg["text"]
  }
  "steward.answer.no-bundle": {
    bundle: Arg["text"]
  }
  "steward.answer.no-code": Record<string, never>
  "steward.answer.no-database": {
    kept: Arg["choice"]
  }
  "steward.answer.no-key": Record<string, never>
  "steward.answer.no-message": {
    bundle: Arg["text"]
    key: Arg["text"]
  }
  "steward.answer.no-preview": {
    key: Arg["text"]
  }
  "steward.answer.no-request": {
    request: Arg["text"]
  }
  "steward.answer.no-service": {
    service: Arg["text"]
  }
  "steward.answer.no-setting": {
    group: Arg["text"]
    path: Arg["text"]
  }
  "steward.answer.no-settings": {
    service: Arg["text"]
    group: Arg["text"]
  }
  "steward.answer.no-sources": Record<string, never>
  "steward.answer.no-subscription": Record<string, never>
  "steward.answer.no-such-key": Record<string, never>
  "steward.answer.not-a-member": Record<string, never>
  "steward.answer.not-admin": Record<string, never>
  "steward.answer.not-an-admin": {
    name: Arg["text"]
  }
  "steward.answer.not-below": Record<string, never>
  "steward.answer.not-in-guild": Record<string, never>
  "steward.answer.not-json": Record<string, never>
  "steward.answer.not-of-type": {
    path: Arg["text"]
    expected: Arg["choice"]
    text: Arg["text"]
  }
  "steward.answer.override-refused": {
    key: Arg["text"]
    language: Arg["text"]
  }
  "steward.answer.playtime-range": {
    most: Arg["duration"]
  }
  "steward.answer.preview-offline": Record<string, never>
  "steward.answer.preview-refused": {
    key: Arg["text"]
    language: Arg["text"]
  }
  "steward.answer.preview-unlinked": Record<string, never>
  "steward.answer.push-not-accepted": Record<string, never>
  "steward.answer.push-unconfigured": Record<string, never>
  "steward.answer.rate-limited": Record<string, never>
  "steward.answer.secret-setting": {
    path: Arg["text"]
  }
  "steward.answer.self": Record<string, never>
  "steward.answer.settings-changed": {
    service: Arg["text"]
    group: Arg["text"]
  }
  "steward.answer.sign-in-elsewhere": Record<string, never>
  "steward.answer.sign-in-refused": {
    status: Arg["number"]
    uri: Arg["text"]
  }
  "steward.answer.sign-in-unconfigured": {
    missing: Arg["text"]
  }
  "steward.answer.subscription-gone": Record<string, never>
  "steward.answer.too-late": Record<string, never>
  "steward.answer.too-long": {
    language: Arg["text"]
    most: Arg["number"]
  }
  "steward.answer.unknown-guild": Record<string, never>
  "steward.answer.unknown-person": Record<string, never>
  "steward.artifact.status": {
    status: Arg["choice"]
  }
  "steward.artifact.status-tip": {
    status: Arg["choice"]
  }
  "steward.backup-settings.daily": {
    days: Arg["number"]
  }
  "steward.backup-settings.monthly": {
    months: Arg["number"]
  }
  "steward.backup-settings.no-at": Record<string, never>
  "steward.backup-settings.no-days": Record<string, never>
  "steward.backup-settings.no-night": Record<string, never>
  "steward.backup-settings.retention": {
    steps: Arg["list"]
    total: Arg["number"]
    sweep: Arg["choice"]
    days: Arg["number"]
  }
  "steward.backup-settings.retention-saved": Record<string, never>
  "steward.backup-settings.schedule-note": Record<string, never>
  "steward.backup-settings.weekly": {
    weeks: Arg["number"]
  }
  "steward.backups.all-partial": Record<string, never>
  "steward.backups.archive": Record<string, never>
  "steward.backups.archives": Record<string, never>
  "steward.backups.back-up-now": Record<string, never>
  "steward.backups.backup": {
    id: Arg["text"]
  }
  "steward.backups.choose-archive": Record<string, never>
  "steward.backups.database": Record<string, never>
  "steward.backups.database-replaced": Record<string, never>
  "steward.backups.download": Record<string, never>
  "steward.backups.download-file": {
    file: Arg["text"]
  }
  "steward.backups.empty-directory": Record<string, never>
  "steward.backups.holds": {
    subject: Arg["text"]
    partial: Arg["choice"]
  }
  "steward.backups.holds-column": Record<string, never>
  "steward.backups.initiated-by": Record<string, never>
  "steward.backups.latest": Record<string, never>
  "steward.backups.next": Record<string, never>
  "steward.backups.no-archive": Record<string, never>
  "steward.backups.no-clock": Record<string, never>
  "steward.backups.no-run": Record<string, never>
  "steward.backups.no-run-note": Record<string, never>
  "steward.backups.no-such-run": Record<string, never>
  "steward.backups.no-such-run-note": Record<string, never>
  "steward.backups.none": Record<string, never>
  "steward.backups.none-finished": Record<string, never>
  "steward.backups.not-tracked": Record<string, never>
  "steward.backups.not-tracked-note": Record<string, never>
  "steward.backups.nothing-to-restore": Record<string, never>
  "steward.backups.restore": Record<string, never>
  "steward.backups.restore-note": Record<string, never>
  "steward.backups.run": Record<string, never>
  "steward.backups.runs": Record<string, never>
  "steward.backups.size": Record<string, never>
  "steward.backups.status": Record<string, never>
  "steward.backups.storage": Record<string, never>
  "steward.backups.title": Record<string, never>
  "steward.backups.took": Record<string, never>
  "steward.backups.type": Record<string, never>
  "steward.backups.unknown": Record<string, never>
  "steward.backups.volume-replaced": Record<string, never>
  "steward.backups.when": Record<string, never>
  "steward.backups.written": Record<string, never>
  "steward.failure.agent-silent": Record<string, never>
  "steward.failure.bot-failed": Record<string, never>
  "steward.failure.bot-silent": Record<string, never>
  "steward.failure.docker-silent": Record<string, never>
  "steward.failure.http": {
    status: Arg["number"]
  }
  "steward.failure.log-lost": Record<string, never>
  "steward.failure.no-points": Record<string, never>
  "steward.failure.not-loaded": Record<string, never>
  "steward.failure.not-requested": Record<string, never>
  "steward.failure.try-again": Record<string, never>
  "steward.failure.unreachable": Record<string, never>
  "steward.form.cancel": Record<string, never>
  "steward.form.changed": {
    count: Arg["number"]
  }
  "steward.form.changed-meanwhile": Record<string, never>
  "steward.form.close": Record<string, never>
  "steward.form.days": Record<string, never>
  "steward.form.done": Record<string, never>
  "steward.form.remove": Record<string, never>
  "steward.form.removing": Record<string, never>
  "steward.form.reset": Record<string, never>
  "steward.form.save": Record<string, never>
  "steward.form.save-count": {
    count: Arg["number"]
  }
  "steward.form.saving": Record<string, never>
  "steward.form.schedule": Record<string, never>
  "steward.form.schedule-saved": Record<string, never>
  "steward.form.sending": Record<string, never>
  "steward.form.waiting": Record<string, never>
  "steward.game.active": Record<string, never>
  "steward.game.all-milestones": Record<string, never>
  "steward.game.complete": Record<string, never>
  "steward.game.complete-ask": {
    name: Arg["text"]
  }
  "steward.game.complete-note": {
    amount: Arg["number"]
    target: Arg["number"]
  }
  "steward.game.counting-down": Record<string, never>
  "steward.game.done-at": {
    at: Arg["text"]
  }
  "steward.game.keep": Record<string, never>
  "steward.game.locked": Record<string, never>
  "steward.game.milestone": Record<string, never>
  "steward.game.milestones": Record<string, never>
  "steward.game.move-down": {
    name: Arg["text"]
  }
  "steward.game.move-up": {
    name: Arg["text"]
  }
  "steward.game.no-round": Record<string, never>
  "steward.game.no-track": Record<string, never>
  "steward.game.objective": Record<string, never>
  "steward.game.outcome": {
    status: Arg["choice"]
  }
  "steward.game.progress": {
    name: Arg["text"]
  }
  "steward.game.registered": {
    count: Arg["number"]
  }
  "steward.game.remove-ask": {
    name: Arg["text"]
  }
  "steward.game.remove-it": Record<string, never>
  "steward.game.round": Record<string, never>
  "steward.game.running": Record<string, never>
  "steward.game.start": {
    anyway: Arg["choice"]
  }
  "steward.game.start-ask": {
    anyway: Arg["choice"]
  }
  "steward.game.start-note": Record<string, never>
  "steward.game.start-round": Record<string, never>
  "steward.game.tasks": {
    finished: Arg["number"]
    total: Arg["number"]
  }
  "steward.game.track": Record<string, never>
  "steward.game.unlock": Record<string, never>
  "steward.game.unlock-ask": {
    name: Arg["text"]
  }
  "steward.game.unlock-note": Record<string, never>
  "steward.game.unlocked": {
    at: Arg["text"]
  }
  "steward.identity.copy": {
    what: Arg["text"]
  }
  "steward.identity.discord-id": Record<string, never>
  "steward.identity.minecraft-uuid": Record<string, never>
  "steward.identity.never-joined": Record<string, never>
  "steward.identity.never-observed": Record<string, never>
  "steward.identity.no-avatar": Record<string, never>
  "steward.identity.no-discord": Record<string, never>
  "steward.identity.no-discord-name": Record<string, never>
  "steward.identity.no-head": Record<string, never>
  "steward.identity.no-minecraft": Record<string, never>
  "steward.identity.no-minecraft-name": Record<string, never>
  "steward.identity.no-name": Record<string, never>
  "steward.image.drift": {
    drift: Arg["choice"]
  }
  "steward.image.drift-tip": {
    drift: Arg["choice"]
  }
  "steward.image.label": Record<string, never>
  "steward.journal.action": Record<string, never>
  "steward.journal.actor": Record<string, never>
  "steward.journal.all": Record<string, never>
  "steward.journal.concerns": Record<string, never>
  "steward.journal.count": {
    count: Arg["number"]
    limit: Arg["number"]
  }
  "steward.journal.detail": Record<string, never>
  "steward.journal.entries": Record<string, never>
  "steward.journal.exact-id": Record<string, never>
  "steward.journal.filter": Record<string, never>
  "steward.journal.no-entry": Record<string, never>
  "steward.journal.reset": Record<string, never>
  "steward.journal.subject": Record<string, never>
  "steward.journal.title": Record<string, never>
  "steward.journal.when": Record<string, never>
  "steward.keys.aborted": Record<string, never>
  "steward.keys.add": Record<string, never>
  "steward.keys.add-note": {
    domain: Arg["text"]
  }
  "steward.keys.add-title": Record<string, never>
  "steward.keys.already-registered": Record<string, never>
  "steward.keys.another-first": Record<string, never>
  "steward.keys.back-to-status": Record<string, never>
  "steward.keys.brand": Record<string, never>
  "steward.keys.browser-cannot": Record<string, never>
  "steward.keys.call-it": Record<string, never>
  "steward.keys.call-this-one": Record<string, never>
  "steward.keys.cancelled": Record<string, never>
  "steward.keys.closed": Record<string, never>
  "steward.keys.did-not-work": Record<string, never>
  "steward.keys.finds-every": Record<string, never>
  "steward.keys.heading": Record<string, never>
  "steward.keys.hold-key": Record<string, never>
  "steward.keys.hold-note": Record<string, never>
  "steward.keys.hold-title": Record<string, never>
  "steward.keys.keep-it": Record<string, never>
  "steward.keys.last-used": {
    at: Arg["instant"]
  }
  "steward.keys.my-iphone": Record<string, never>
  "steward.keys.my-key": Record<string, never>
  "steward.keys.my-mac": Record<string, never>
  "steward.keys.my-phone": Record<string, never>
  "steward.keys.new-name": {
    key: Arg["text"]
  }
  "steward.keys.no-answer": Record<string, never>
  "steward.keys.no-key": Record<string, never>
  "steward.keys.no-key-returned": Record<string, never>
  "steward.keys.no-keys-here": Record<string, never>
  "steward.keys.no-keys-note": Record<string, never>
  "steward.keys.nobody-can-sign-in": Record<string, never>
  "steward.keys.not-accepted": Record<string, never>
  "steward.keys.not-backed-up": Record<string, never>
  "steward.keys.not-found": Record<string, never>
  "steward.keys.not-held": Record<string, never>
  "steward.keys.not-now": Record<string, never>
  "steward.keys.not-registered": Record<string, never>
  "steward.keys.not-set": {
    setting: Arg["text"]
  }
  "steward.keys.open-directly": Record<string, never>
  "steward.keys.register": Record<string, never>
  "steward.keys.register-this": Record<string, never>
  "steward.keys.registered": {
    at: Arg["instant"]
  }
  "steward.keys.registration-incomplete": Record<string, never>
  "steward.keys.remove": {
    key: Arg["text"]
  }
  "steward.keys.remove-it": Record<string, never>
  "steward.keys.remove-note": {
    only: Arg["choice"]
  }
  "steward.keys.remove-title": {
    key: Arg["text"]
  }
  "steward.keys.rename": {
    key: Arg["text"]
  }
  "steward.keys.rename-note": Record<string, never>
  "steward.keys.rename-title": Record<string, never>
  "steward.keys.season": Record<string, never>
  "steward.keys.second-key": Record<string, never>
  "steward.keys.setup-title": Record<string, never>
  "steward.keys.sign-in": Record<string, never>
  "steward.keys.sign-in-incomplete": Record<string, never>
  "steward.keys.sign-out-instead": Record<string, never>
  "steward.keys.signed-in-as": {
    name: Arg["text"]
  }
  "steward.keys.step-up-note": {
    minutes: Arg["number"]
  }
  "steward.keys.step-up-title": Record<string, never>
  "steward.keys.try-again": Record<string, never>
  "steward.keys.unexplained": Record<string, never>
  "steward.keys.unsupported": Record<string, never>
  "steward.keys.use-key": Record<string, never>
  "steward.keys.waiting": Record<string, never>
  "steward.keys.with-discord": Record<string, never>
  "steward.keys.wrong-domain": Record<string, never>
  "steward.message-editor.action": Record<string, never>
  "steward.message-editor.add-colour": Record<string, never>
  "steward.message-editor.add-variant": Record<string, never>
  "steward.message-editor.apply": Record<string, never>
  "steward.message-editor.as-it-looks": Record<string, never>
  "steward.message-editor.bold": Record<string, never>
  "steward.message-editor.clear-formatting": Record<string, never>
  "steward.message-editor.click": Record<string, never>
  "steward.message-editor.click-value": Record<string, never>
  "steward.message-editor.code": Record<string, never>
  "steward.message-editor.colour": Record<string, never>
  "steward.message-editor.copy-to-clipboard": Record<string, never>
  "steward.message-editor.default-style": Record<string, never>
  "steward.message-editor.empty": Record<string, never>
  "steward.message-editor.fallen-back": Record<string, never>
  "steward.message-editor.glyph": Record<string, never>
  "steward.message-editor.gradient": Record<string, never>
  "steward.message-editor.hex-colour": Record<string, never>
  "steward.message-editor.hover": Record<string, never>
  "steward.message-editor.italic": Record<string, never>
  "steward.message-editor.line-break": Record<string, never>
  "steward.message-editor.link": Record<string, never>
  "steward.message-editor.no-colour": Record<string, never>
  "steward.message-editor.obfuscated": Record<string, never>
  "steward.message-editor.open-url": Record<string, never>
  "steward.message-editor.override": Record<string, never>
  "steward.message-editor.packaged-now": Record<string, never>
  "steward.message-editor.packaged-then": Record<string, never>
  "steward.message-editor.placeholder": Record<string, never>
  "steward.message-editor.remove": Record<string, never>
  "steward.message-editor.remove-colour": Record<string, never>
  "steward.message-editor.remove-variant": Record<string, never>
  "steward.message-editor.run-command": Record<string, never>
  "steward.message-editor.send-in-discord": Record<string, never>
  "steward.message-editor.show-in-game": Record<string, never>
  "steward.message-editor.source": Record<string, never>
  "steward.message-editor.strikethrough": Record<string, never>
  "steward.message-editor.suggest-command": Record<string, never>
  "steward.message-editor.take-over": Record<string, never>
  "steward.message-editor.tone": Record<string, never>
  "steward.message-editor.underlined": Record<string, never>
  "steward.message-editor.value-style": Record<string, never>
  "steward.message-editor.variant": Record<string, never>
  "steward.network.cpu": {
    percent: Arg["text"]
  }
  "steward.network.memory": {
    used: Arg["text"]
  }
  "steward.network.memory-of": {
    used: Arg["text"]
    limit: Arg["text"]
  }
  "steward.network.more": {
    count: Arg["number"]
  }
  "steward.network.no-image": Record<string, never>
  "steward.network.no-name": Record<string, never>
  "steward.network.no-service": Record<string, never>
  "steward.network.no-service-note": Record<string, never>
  "steward.network.not-running": Record<string, never>
  "steward.network.open": {
    service: Arg["text"]
  }
  "steward.network.players": Record<string, never>
  "steward.network.title": Record<string, never>
  "steward.network.up": {
    since: Arg["text"]
  }
  "steward.notifications.added": {
    at: Arg["instant"]
  }
  "steward.notifications.by-channel": {
    type: Arg["text"]
    channel: Arg["text"]
  }
  "steward.notifications.devices": Record<string, never>
  "steward.notifications.discord": Record<string, never>
  "steward.notifications.disk-in-use": Record<string, never>
  "steward.notifications.last-notified": {
    at: Arg["instant"]
  }
  "steward.notifications.memory-in-use": Record<string, never>
  "steward.notifications.newest-backup": Record<string, never>
  "steward.notifications.no-device": Record<string, never>
  "steward.notifications.no-push": Record<string, never>
  "steward.notifications.notify-about": Record<string, never>
  "steward.notifications.off": Record<string, never>
  "steward.notifications.on": Record<string, never>
  "steward.notifications.push": Record<string, never>
  "steward.notifications.read-only": {
    group: Arg["text"]
    service: Arg["text"]
  }
  "steward.notifications.remove-device": {
    name: Arg["text"]
  }
  "steward.notifications.sample": {
    type: Arg["choice"]
  }
  "steward.notifications.send-test": {
    name: Arg["text"]
  }
  "steward.notifications.tell-me-when": Record<string, never>
  "steward.notifications.test-notifications": Record<string, never>
  "steward.notifications.this-device": Record<string, never>
  "steward.notifications.this-one": Record<string, never>
  "steward.notifications.title": Record<string, never>
  "steward.notifications.turn-off": Record<string, never>
  "steward.notifications.turn-on": Record<string, never>
  "steward.notifications.type": {
    type: Arg["choice"]
  }
  "steward.notifications.unnamed": Record<string, never>
  "steward.operations.a-run": Record<string, never>
  "steward.operations.added": Record<string, never>
  "steward.operations.all-updates": Record<string, never>
  "steward.operations.ask": {
    kind: Arg["choice"]
  }
  "steward.operations.ask-warning": {
    kind: Arg["choice"]
  }
  "steward.operations.ask-what": {
    kind: Arg["choice"]
  }
  "steward.operations.cancel": Record<string, never>
  "steward.operations.cancelled": {
    run: Arg["number"]
  }
  "steward.operations.cancelled-note": Record<string, never>
  "steward.operations.cannot-copy": Record<string, never>
  "steward.operations.cannot-copy-note": Record<string, never>
  "steward.operations.changes": Record<string, never>
  "steward.operations.command-copied": Record<string, never>
  "steward.operations.copied": Record<string, never>
  "steward.operations.copy": Record<string, never>
  "steward.operations.duration": Record<string, never>
  "steward.operations.entered": {
    kind: Arg["text"]
    run: Arg["number"]
  }
  "steward.operations.failed": {
    count: Arg["number"]
  }
  "steward.operations.growing": Record<string, never>
  "steward.operations.moved": {
    services: Arg["number"]
    artefacts: Arg["number"]
  }
  "steward.operations.no-build": Record<string, never>
  "steward.operations.no-change": Record<string, never>
  "steward.operations.no-earlier-than": Record<string, never>
  "steward.operations.no-earlier-than-hint": Record<string, never>
  "steward.operations.no-line": Record<string, never>
  "steward.operations.no-line-note": Record<string, never>
  "steward.operations.no-line-title": Record<string, never>
  "steward.operations.no-report": Record<string, never>
  "steward.operations.no-report-note": Record<string, never>
  "steward.operations.not-a-number": Record<string, never>
  "steward.operations.not-a-number-note": {
    id: Arg["text"]
  }
  "steward.operations.not-cancelled": {
    run: Arg["number"]
  }
  "steward.operations.notes": Record<string, never>
  "steward.operations.nothing-to-do": Record<string, never>
  "steward.operations.nothing-to-do-note": Record<string, never>
  "steward.operations.nothing-to-do-title": Record<string, never>
  "steward.operations.nothing-written": Record<string, never>
  "steward.operations.now": Record<string, never>
  "steward.operations.raw-report": Record<string, never>
  "steward.operations.report": Record<string, never>
  "steward.operations.report-unreadable": Record<string, never>
  "steward.operations.requested-by": Record<string, never>
  "steward.operations.run": {
    id: Arg["text"]
  }
  "steward.operations.saved": {
    count: Arg["number"]
  }
  "steward.operations.saved-nothing": Record<string, never>
  "steward.operations.saved-nothing-note": Record<string, never>
  "steward.operations.scoped": {
    ask: Arg["text"]
    services: Arg["list"]
  }
  "steward.operations.service": Record<string, never>
  "steward.operations.stages": Record<string, never>
  "steward.operations.started": Record<string, never>
  "steward.operations.state": Record<string, never>
  "steward.operations.status": Record<string, never>
  "steward.operations.step": {
    stage: Arg["choice"]
  }
  "steward.operations.still-running": Record<string, never>
  "steward.operations.under-way": {
    kind: Arg["text"]
    run: Arg["number"]
  }
  "steward.overview.all-clear": Record<string, never>
  "steward.overview.authored": Record<string, never>
  "steward.overview.behind": Record<string, never>
  "steward.overview.cores": {
    count: Arg["number"]
  }
  "steward.overview.issues": Record<string, never>
  "steward.overview.latest-actions": Record<string, never>
  "steward.overview.latest-backup": Record<string, never>
  "steward.overview.memory": Record<string, never>
  "steward.overview.no-finished-backup": Record<string, never>
  "steward.overview.none": Record<string, never>
  "steward.overview.nothing-recorded": Record<string, never>
  "steward.overview.nothing-recorded-note": Record<string, never>
  "steward.overview.partly-unreadable": Record<string, never>
  "steward.overview.unreadable": Record<string, never>
  "steward.overview.used-of": {
    used: Arg["text"]
    total: Arg["text"]
  }
  "steward.overview.whole-journal": Record<string, never>
  "steward.payments.all": Record<string, never>
  "steward.payments.amount": Record<string, never>
  "steward.payments.created": {
    at: Arg["instant"]
  }
  "steward.payments.days": Record<string, never>
  "steward.payments.deadline": Record<string, never>
  "steward.payments.donation": Record<string, never>
  "steward.payments.no-payment": {
    reference: Arg["text"]
  }
  "steward.payments.no-request": Record<string, never>
  "steward.payments.no-request-note": Record<string, never>
  "steward.payments.no-tab": Record<string, never>
  "steward.payments.none-with-status": Record<string, never>
  "steward.payments.none-with-status-note": Record<string, never>
  "steward.payments.not-open": {
    reference: Arg["text"]
  }
  "steward.payments.not-open-note": {
    was: Arg["text"]
  }
  "steward.payments.not-the-balance": Record<string, never>
  "steward.payments.nothing-booked": Record<string, never>
  "steward.payments.nothing-settled": Record<string, never>
  "steward.payments.open": Record<string, never>
  "steward.payments.overdue": Record<string, never>
  "steward.payments.overdue-note": {
    count: Arg["number"]
  }
  "steward.payments.overdue-tip": Record<string, never>
  "steward.payments.paid": Record<string, never>
  "steward.payments.past-deadline": {
    count: Arg["number"]
  }
  "steward.payments.person": Record<string, never>
  "steward.payments.reference": Record<string, never>
  "steward.payments.requested": Record<string, never>
  "steward.payments.requested-hint": Record<string, never>
  "steward.payments.requests": Record<string, never>
  "steward.payments.settle": Record<string, never>
  "steward.payments.settle-ask": Record<string, never>
  "steward.payments.settle-note": {
    reference: Arg["text"]
  }
  "steward.payments.settled": {
    reference: Arg["text"]
  }
  "steward.payments.settled-note": {
    days: Arg["number"]
    until: Arg["instant"]
  }
  "steward.payments.state": {
    status: Arg["choice"]
  }
  "steward.payments.state-tip": {
    status: Arg["choice"]
  }
  "steward.payments.status": Record<string, never>
  "steward.payments.tab": Record<string, never>
  "steward.payments.title": Record<string, never>
  "steward.people.access": Record<string, never>
  "steward.people.access-tip": Record<string, never>
  "steward.people.actions-for": {
    name: Arg["text"]
  }
  "steward.people.active-tip": {
    until: Arg["instant"]
  }
  "steward.people.active-until": {
    until: Arg["instant"]
  }
  "steward.people.admin": Record<string, never>
  "steward.people.appended": Record<string, never>
  "steward.people.begins": {
    at: Arg["instant"]
  }
  "steward.people.begins-tip": Record<string, never>
  "steward.people.below-too": {
    count: Arg["number"]
  }
  "steward.people.chain": Record<string, never>
  "steward.people.counted": {
    counted: Arg["duration"]
    becoming: Arg["duration"]
    usable: Arg["choice"]
  }
  "steward.people.day-is-day": Record<string, never>
  "steward.people.discord-id": Record<string, never>
  "steward.people.entry-stays": Record<string, never>
  "steward.people.expired": {
    at: Arg["instant"]
  }
  "steward.people.expired-tip": Record<string, never>
  "steward.people.filter": Record<string, never>
  "steward.people.filter-name": Record<string, never>
  "steward.people.from-launch": Record<string, never>
  "steward.people.grant": Record<string, never>
  "steward.people.grant-access": Record<string, never>
  "steward.people.grant-note": Record<string, never>
  "steward.people.grant-title": Record<string, never>
  "steward.people.granted": Record<string, never>
  "steward.people.granted-by": {
    name: Arg["text"]
  }
  "steward.people.guild": Record<string, never>
  "steward.people.hours": Record<string, never>
  "steward.people.id-example": Record<string, never>
  "steward.people.journal-names-you": Record<string, never>
  "steward.people.language": Record<string, never>
  "steward.people.last-changed": {
    at: Arg["instant"]
  }
  "steward.people.linked-at": {
    at: Arg["instant"]
  }
  "steward.people.make-admin": Record<string, never>
  "steward.people.make-admin-note": Record<string, never>
  "steward.people.make-admin-title": {
    name: Arg["text"]
  }
  "steward.people.member": {
    state: Arg["choice"]
  }
  "steward.people.member-tip": {
    state: Arg["choice"]
  }
  "steward.people.minecraft": Record<string, never>
  "steward.people.minutes": Record<string, never>
  "steward.people.most-days": {
    most: Arg["number"]
  }
  "steward.people.never": Record<string, never>
  "steward.people.never-tip": Record<string, never>
  "steward.people.next": Record<string, never>
  "steward.people.no-access": Record<string, never>
  "steward.people.no-access-tip": {
    until: Arg["instant"]
  }
  "steward.people.no-longer-admin": Record<string, never>
  "steward.people.no-match": Record<string, never>
  "steward.people.no-match-note": {
    withAccess: Arg["choice"]
  }
  "steward.people.no-pack": Record<string, never>
  "steward.people.no-pack-tip": {
    by: Arg["text"]
    at: Arg["instant"]
  }
  "steward.people.no-period": Record<string, never>
  "steward.people.no-period-note": Record<string, never>
  "steward.people.no-refund": Record<string, never>
  "steward.people.nobody": Record<string, never>
  "steward.people.nobody-note": Record<string, never>
  "steward.people.none-linked": Record<string, never>
  "steward.people.none-running": Record<string, never>
  "steward.people.not-granted": Record<string, never>
  "steward.people.not-linked": Record<string, never>
  "steward.people.not-linked-tip": {
    paid: Arg["choice"]
  }
  "steward.people.not-made-admin": Record<string, never>
  "steward.people.not-revoked": Record<string, never>
  "steward.people.not-unlinked": Record<string, never>
  "steward.people.nothing-changed": Record<string, never>
  "steward.people.nothing-to-revoke": Record<string, never>
  "steward.people.nothing-to-unlink": Record<string, never>
  "steward.people.over": Record<string, never>
  "steward.people.over-tip": Record<string, never>
  "steward.people.pack": {
    exempted: Arg["choice"]
  }
  "steward.people.pack-changed": {
    exempted: Arg["choice"]
  }
  "steward.people.pack-note": {
    exempted: Arg["choice"]
  }
  "steward.people.pack-title": {
    exempted: Arg["choice"]
    name: Arg["text"]
  }
  "steward.people.page": {
    page: Arg["number"]
    pages: Arg["number"]
  }
  "steward.people.periods": Record<string, never>
  "steward.people.person": Record<string, never>
  "steward.people.playtime": Record<string, never>
  "steward.people.playtime-from": {
    time: Arg["duration"]
  }
  "steward.people.playtime-not-written": Record<string, never>
  "steward.people.playtime-note": {
    name: Arg["text"]
  }
  "steward.people.playtime-set": {
    name: Arg["text"]
  }
  "steward.people.playtime-title": Record<string, never>
  "steward.people.previous": Record<string, never>
  "steward.people.request": Record<string, never>
  "steward.people.request-gone": {
    source: Arg["choice"]
  }
  "steward.people.revoke": Record<string, never>
  "steward.people.revoke-admin": Record<string, never>
  "steward.people.revoke-admin-note": {
    branch: Arg["number"]
  }
  "steward.people.revoke-admin-title": {
    name: Arg["text"]
  }
  "steward.people.revoke-note": Record<string, never>
  "steward.people.revoke-title": Record<string, never>
  "steward.people.revoked": {
    at: Arg["instant"]
  }
  "steward.people.revoked-for": {
    count: Arg["number"]
  }
  "steward.people.revoked-tip": Record<string, never>
  "steward.people.roles": Record<string, never>
  "steward.people.root-admin": Record<string, never>
  "steward.people.roster": Record<string, never>
  "steward.people.running": Record<string, never>
  "steward.people.running-tip": Record<string, never>
  "steward.people.shown": {
    shown: Arg["number"]
    loaded: Arg["number"]
  }
  "steward.people.some-admin": Record<string, never>
  "steward.people.source": {
    source: Arg["choice"]
  }
  "steward.people.source-column": Record<string, never>
  "steward.people.state": Record<string, never>
  "steward.people.supporter": Record<string, never>
  "steward.people.supporter-tip": Record<string, never>
  "steward.people.thrown-out": Record<string, never>
  "steward.people.title": Record<string, never>
  "steward.people.unknown-id": Record<string, never>
  "steward.people.unlink": Record<string, never>
  "steward.people.unlink-note": Record<string, never>
  "steward.people.unlink-title": Record<string, never>
  "steward.people.unlinked": Record<string, never>
  "steward.people.valid-until": {
    until: Arg["instant"]
  }
  "steward.people.window": Record<string, never>
  "steward.people.with-access": Record<string, never>
  "steward.said.announced": {
    posted: Arg["choice"]
    language: Arg["text"]
  }
  "steward.said.guild-not-listed": Record<string, never>
  "steward.said.message": Record<string, never>
  "steward.said.no-bot-token": Record<string, never>
  "steward.said.no-guild-id": Record<string, never>
  "steward.said.preview-sent": Record<string, never>
  "steward.said.setting": {
    network: Arg["choice"]
    service: Arg["text"]
    live: Arg["choice"]
  }
  "steward.said.words": {
    text: Arg["text"]
  }
  "steward.season.current": Record<string, never>
  "steward.season.date-removed": {
    date: Arg["text"]
  }
  "steward.season.date-saved": {
    date: Arg["text"]
  }
  "steward.season.dates": Record<string, never>
  "steward.season.lands-on": {
    where: Arg["text"]
  }
  "steward.season.launch": Record<string, never>
  "steward.season.launch-note": Record<string, never>
  "steward.season.launch-removal": Record<string, never>
  "steward.season.network": Record<string, never>
  "steward.season.no-date": Record<string, never>
  "steward.season.nothing-carried": Record<string, never>
  "steward.season.now": Record<string, never>
  "steward.season.phase": {
    phase: Arg["choice"]
  }
  "steward.season.phase-heading": Record<string, never>
  "steward.season.phase-is-now": {
    phase: Arg["text"]
  }
  "steward.season.reason": Record<string, never>
  "steward.season.reason-note": Record<string, never>
  "steward.season.reason-placeholder": Record<string, never>
  "steward.season.rebuild": Record<string, never>
  "steward.season.remove-title": {
    date: Arg["text"]
  }
  "steward.season.saved-at": {
    at: Arg["instant"]
  }
  "steward.season.smp-start": Record<string, never>
  "steward.season.smp-start-note": Record<string, never>
  "steward.season.smp-start-removal": Record<string, never>
  "steward.season.switch-note": {
    who: Arg["text"]
  }
  "steward.season.switch-phase": Record<string, never>
  "steward.season.switch-title": {
    phase: Arg["text"]
  }
  "steward.season.switching": Record<string, never>
  "steward.season.title": Record<string, never>
  "steward.season.who": {
    phase: Arg["choice"]
  }
  "steward.service-page.add-plugin": Record<string, never>
  "steward.service-page.added": Record<string, never>
  "steward.service-page.agent-not-yet": Record<string, never>
  "steward.service-page.agent-silent": Record<string, never>
  "steward.service-page.agent-unknown": Record<string, never>
  "steward.service-page.arrives": {
    file: Arg["text"]
  }
  "steward.service-page.check-updates": Record<string, never>
  "steward.service-page.close-find": Record<string, never>
  "steward.service-page.commands": Record<string, never>
  "steward.service-page.console": Record<string, never>
  "steward.service-page.cpu": Record<string, never>
  "steward.service-page.disk": Record<string, never>
  "steward.service-page.download": Record<string, never>
  "steward.service-page.find": Record<string, never>
  "steward.service-page.find-in": Record<string, never>
  "steward.service-page.given": Record<string, never>
  "steward.service-page.given-tip": Record<string, never>
  "steward.service-page.going-offline": Record<string, never>
  "steward.service-page.group": {
    group: Arg["choice"]
  }
  "steward.service-page.held-back": Record<string, never>
  "steward.service-page.install": {
    title: Arg["text"]
  }
  "steward.service-page.installed-by": {
    release: Arg["text"]
  }
  "steward.service-page.line-count": {
    lines: Arg["number"]
  }
  "steward.service-page.lines": Record<string, never>
  "steward.service-page.log-unreachable": Record<string, never>
  "steward.service-page.more-actions": Record<string, never>
  "steward.service-page.newest": Record<string, never>
  "steward.service-page.no-build": Record<string, never>
  "steward.service-page.no-build-for": {
    version: Arg["text"]
  }
  "steward.service-page.no-volume": Record<string, never>
  "steward.service-page.not-added": Record<string, never>
  "steward.service-page.not-installed": Record<string, never>
  "steward.service-page.not-sent": Record<string, never>
  "steward.service-page.not-yet-installed": Record<string, never>
  "steward.service-page.nothing-found": Record<string, never>
  "steward.service-page.nothing-installed": Record<string, never>
  "steward.service-page.nothing-on": {
    loader: Arg["text"]
    version: Arg["text"]
  }
  "steward.service-page.offline": Record<string, never>
  "steward.service-page.on-modrinth": {
    title: Arg["text"]
  }
  "steward.service-page.plugin-added": {
    title: Arg["text"]
  }
  "steward.service-page.plugins": Record<string, never>
  "steward.service-page.ram": Record<string, never>
  "steward.service-page.range": Record<string, never>
  "steward.service-page.reading": {
    metric: Arg["text"]
    at: Arg["instant"]
  }
  "steward.service-page.recreate-note": Record<string, never>
  "steward.service-page.recreate-service": {
    service: Arg["text"]
  }
  "steward.service-page.recreate-tip": {
    service: Arg["text"]
  }
  "steward.service-page.recreate-title": {
    service: Arg["text"]
  }
  "steward.service-page.remove-jar": {
    jar: Arg["text"]
  }
  "steward.service-page.remove-jar-and-folder": {
    jar: Arg["text"]
    folder: Arg["text"]
  }
  "steward.service-page.remove-plugin": {
    name: Arg["text"]
  }
  "steward.service-page.remove-title": {
    name: Arg["text"]
  }
  "steward.service-page.search-modrinth": Record<string, never>
  "steward.service-page.search-placeholder": Record<string, never>
  "steward.service-page.send": Record<string, never>
  "steward.service-page.send-line": Record<string, never>
  "steward.service-page.settings": Record<string, never>
  "steward.service-page.settings-and-texts": Record<string, never>
  "steward.service-page.the-jar": Record<string, never>
  "steward.service-page.uncheckable": Record<string, never>
  "steward.service-page.up-to-date": Record<string, never>
  "steward.service-page.update-available": Record<string, never>
  "steward.service-page.waiting-for-log": Record<string, never>
  "steward.service.docker-state": {
    state: Arg["text"]
  }
  "steward.service.health": {
    health: Arg["text"]
  }
  "steward.service.held-since": {
    since: Arg["instant"]
    state: Arg["text"]
  }
  "steward.service.no-health": Record<string, never>
  "steward.service.not-read": Record<string, never>
  "steward.service.starting": Record<string, never>
  "steward.service.state": {
    state: Arg["choice"]
  }
  "steward.service.unhealthy": Record<string, never>
  "steward.settings.add": Record<string, never>
  "steward.settings.add-entry": Record<string, never>
  "steward.settings.add-to": {
    what: Arg["text"]
  }
  "steward.settings.all": Record<string, never>
  "steward.settings.back-to-files": Record<string, never>
  "steward.settings.back-to-top": Record<string, never>
  "steward.settings.cannot-remove": Record<string, never>
  "steward.settings.changed": Record<string, never>
  "steward.settings.choose-one": Record<string, never>
  "steward.settings.choose-suggestion": Record<string, never>
  "steward.settings.draft-only": Record<string, never>
  "steward.settings.empty-file": Record<string, never>
  "steward.settings.empty-list": Record<string, never>
  "steward.settings.entry": {
    index: Arg["number"]
  }
  "steward.settings.entry-cannot-remove": {
    index: Arg["number"]
  }
  "steward.settings.env-override": Record<string, never>
  "steward.settings.env-override-tip": Record<string, never>
  "steward.settings.every-tag": Record<string, never>
  "steward.settings.files": Record<string, never>
  "steward.settings.free-text": Record<string, never>
  "steward.settings.incomplete": {
    missing: Arg["text"]
  }
  "steward.settings.no-colour": Record<string, never>
  "steward.settings.no-entries": Record<string, never>
  "steward.settings.no-files": Record<string, never>
  "steward.settings.no-match": Record<string, never>
  "steward.settings.no-such-file": Record<string, never>
  "steward.settings.none": Record<string, never>
  "steward.settings.not-in-bundle": Record<string, never>
  "steward.settings.not-readable": Record<string, never>
  "steward.settings.not-set": Record<string, never>
  "steward.settings.overridden": Record<string, never>
  "steward.settings.pick-colour": Record<string, never>
  "steward.settings.preview": {
    shown: Arg["choice"]
  }
  "steward.settings.preview-on": Record<string, never>
  "steward.settings.raw-list": Record<string, never>
  "steward.settings.read-only": Record<string, never>
  "steward.settings.refused": {
    problem: Arg["text"]
  }
  "steward.settings.remove": {
    what: Arg["text"]
  }
  "steward.settings.remove-ask": {
    title: Arg["text"]
  }
  "steward.settings.remove-entry": {
    index: Arg["number"]
  }
  "steward.settings.remove-entry-ask": Record<string, never>
  "steward.settings.reset-to-packaged": Record<string, never>
  "steward.settings.restart-needed": Record<string, never>
  "steward.settings.search": Record<string, never>
  "steward.settings.search-file": Record<string, never>
  "steward.settings.search-in": {
    what: Arg["text"]
  }
  "steward.settings.set": Record<string, never>
  "steward.settings.settings-saved": {
    count: Arg["number"]
  }
  "steward.settings.tag": Record<string, never>
  "steward.settings.texts-saved": {
    count: Arg["number"]
  }
  "steward.settings.undo": Record<string, never>
  "steward.settings.undo-entry": {
    what: Arg["text"]
  }
  "steward.shell.account": Record<string, never>
  "steward.shell.account-of": {
    name: Arg["text"]
  }
  "steward.shell.discord": {
    id: Arg["text"]
  }
  "steward.shell.jump": Record<string, never>
  "steward.shell.navigation": Record<string, never>
  "steward.shell.note": {
    page: Arg["choice"]
  }
  "steward.shell.nothing-found": Record<string, never>
  "steward.shell.page": {
    page: Arg["choice"]
  }
  "steward.shell.pages": Record<string, never>
  "steward.shell.run-hit": {
    run: Arg["number"]
    kind: Arg["text"]
  }
  "steward.shell.run-when": {
    status: Arg["text"]
    requested: Arg["instant"]
  }
  "steward.shell.runs": Record<string, never>
  "steward.shell.search": Record<string, never>
  "steward.shell.search-label": Record<string, never>
  "steward.shell.search-pages": Record<string, never>
  "steward.shell.search-placeholder": Record<string, never>
  "steward.shell.service-note": {
    name: Arg["text"]
  }
  "steward.shell.settings": Record<string, never>
  "steward.shell.sign-out": Record<string, never>
  "steward.shell.steward": Record<string, never>
  "steward.shell.still-reading": Record<string, never>
  "steward.shell.stuck": Record<string, never>
  "steward.shell.unknown": Record<string, never>
  "steward.updates.available": Record<string, never>
  "steward.updates.change": Record<string, never>
  "steward.updates.check-again": Record<string, never>
  "steward.updates.check-again-failed": Record<string, never>
  "steward.updates.check-again-tip": Record<string, never>
  "steward.updates.checked": Record<string, never>
  "steward.updates.incomplete": Record<string, never>
  "steward.updates.initiated-by": Record<string, never>
  "steward.updates.kind": Record<string, never>
  "steward.updates.next": Record<string, never>
  "steward.updates.no-day": Record<string, never>
  "steward.updates.no-run": Record<string, never>
  "steward.updates.no-run-note": Record<string, never>
  "steward.updates.no-section": Record<string, never>
  "steward.updates.no-section-note": Record<string, never>
  "steward.updates.not-scheduled": Record<string, never>
  "steward.updates.nothing": Record<string, never>
  "steward.updates.nothing-to-install": Record<string, never>
  "steward.updates.nothing-to-install-note": Record<string, never>
  "steward.updates.plugin": Record<string, never>
  "steward.updates.resource-pack": Record<string, never>
  "steward.updates.restart-everything": Record<string, never>
  "steward.updates.result": Record<string, never>
  "steward.updates.run": Record<string, never>
  "steward.updates.runs": Record<string, never>
  "steward.updates.schedule-note": Record<string, never>
  "steward.updates.service": Record<string, never>
  "steward.updates.source-silent": Record<string, never>
  "steward.updates.state": Record<string, never>
  "steward.updates.status": Record<string, never>
  "steward.updates.title": Record<string, never>
  "steward.updates.unclaimed": {
    files: Arg["list"]
  }
  "steward.updates.update-everything": Record<string, never>
  "steward.updates.when": Record<string, never>
}
