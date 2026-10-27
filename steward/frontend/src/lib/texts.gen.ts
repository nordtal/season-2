/** The values each of Steward's texts takes; written by `./gradlew :steward:generateApiTypes`. */
import type { Arg } from "@/lib/texts"

export type TextArgs = {
  "alert.age": {
    subject: Arg["text"]
    hours: Arg["number"]
    limit: Arg["number"]
  }
  "alert.claimed": {
    payment: Arg["text"]
    amount: Arg["money"]
    reference: Arg["text"]
  }
  "alert.clear": {
    type: Arg["choice"]
  }
  "alert.dead-pack": Record<string, never>
  "alert.disk": Record<string, never>
  "alert.disk-use": {
    percent: Arg["number"]
    threshold: Arg["number"]
  }
  "alert.dm": Record<string, never>
  "alert.docker-state": {
    service: Arg["text"]
    state: Arg["text"]
  }
  "alert.exit-code": {
    service: Arg["text"]
    code: Arg["number"]
  }
  "alert.failed-for": {
    person: Arg["mention"]
    error: Arg["text"]
  }
  "alert.failed-in": {
    channel: Arg["text"]
    error: Arg["text"]
  }
  "alert.failed-once": {
    service: Arg["text"]
  }
  "alert.health-fails": {
    service: Arg["text"]
  }
  "alert.level": {
    level: Arg["choice"]
  }
  "alert.link-failed": Record<string, never>
  "alert.link-refused": Record<string, never>
  "alert.lock-not-kept": Record<string, never>
  "alert.lock-without-channel": Record<string, never>
  "alert.memory": Record<string, never>
  "alert.memory-use": {
    percent: Arg["number"]
    threshold: Arg["number"]
  }
  "alert.missing": {
    subject: Arg["text"]
  }
  "alert.no-archive": {
    volume: Arg["text"]
  }
  "alert.no-backup": Record<string, never>
  "alert.no-dump": Record<string, never>
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
  "alert.offered-pack": {
    url: Arg["text"]
  }
  "alert.old-archive": {
    volume: Arg["text"]
  }
  "alert.old-dump": Record<string, never>
  "alert.old-offsite": Record<string, never>
  "alert.older-image": {
    services: Arg["list"]
    count: Arg["number"]
  }
  "alert.payment": Record<string, never>
  "alert.purchase-failed": Record<string, never>
  "alert.refused": {
    reference: Arg["text"]
    refusal: Arg["choice"]
  }
  "alert.restarted-by-docker": {
    service: Arg["text"]
    count: Arg["number"]
  }
  "alert.restarting": {
    service: Arg["text"]
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
  "alert.several": {
    subjects: Arg["list"]
  }
  "alert.step": {
    kind: Arg["message"]
    why: Arg["message"]
  }
  "alert.step-on": {
    kind: Arg["message"]
    service: Arg["text"]
    why: Arg["message"]
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
  "check.block.no-value": {
    block: Arg["text"]
    name: Arg["text"]
    offered: Arg["list"]
  }
  "check.block.other-kind": {
    block: Arg["text"]
    name: Arg["text"]
    kind: Arg["text"]
    given: Arg["text"]
  }
  "check.block.unbound": {
    block: Arg["text"]
    name: Arg["text"]
    offered: Arg["list"]
  }
  "check.block.unknown": {
    block: Arg["text"]
  }
  "check.synonym": {
    word: Arg["text"]
    term: Arg["text"]
  }
  "check.syntax.attribute-missing": {
    name: Arg["text"]
    at: Arg["number"]
  }
  "check.syntax.binding-incomplete": {
    block: Arg["text"]
    at: Arg["number"]
  }
  "check.syntax.binding-twice": {
    block: Arg["text"]
    name: Arg["text"]
    at: Arg["number"]
  }
  "check.syntax.block-in-argument": {
    at: Arg["number"]
  }
  "check.syntax.block-not-closed": {
    block: Arg["text"]
    at: Arg["number"]
  }
  "check.syntax.block-unnamed": {
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
  "check.tag.line-break-on-one-line": {
    tag: Arg["text"]
  }
  "check.tag.link-shows-address": {
    name: Arg["text"]
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
  "journal.add-authenticator": Record<string, never>
  "journal.add-plugin": {
    service: Arg["text"]
    artifact: Arg["text"]
  }
  "journal.admin-root": Record<string, never>
  "journal.announce": {
    languages: Arg["list"]
  }
  "journal.cancel-run": {
    run: Arg["number"]
    kind: Arg["choice"]
  }
  "journal.clear-launch": Record<string, never>
  "journal.clear-smp-start": Record<string, never>
  "journal.complete-objective": {
    objective: Arg["text"]
  }
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
  "journal.forget-every-factor": {
    keys: Arg["number"]
    authenticator: Arg["choice"]
    sessions: Arg["number"]
  }
  "journal.grant-access": {
    days: Arg["number"]
    until: Arg["instant"]
  }
  "journal.grant-admin": Record<string, never>
  "journal.held-key-for": {
    label: Arg["text"]
    unlocked: Arg["choice"]
    hold: Arg["duration"]
  }
  "journal.link": Record<string, never>
  "journal.link-codes-exhausted": Record<string, never>
  "journal.register-key": {
    label: Arg["text"]
  }
  "journal.remove-authenticator": {
    keys: Arg["number"]
  }
  "journal.remove-key": {
    label: Arg["text"]
    left: Arg["number"]
  }
  "journal.remove-plugin": {
    run: Arg["number"]
    service: Arg["text"]
    artifact: Arg["text"]
  }
  "journal.remove-stack": {
    item: Arg["text"]
    amount: Arg["number"]
  }
  "journal.rename-key": {
    label: Arg["text"]
  }
  "journal.request-restore": {
    run: Arg["number"]
    archive: Arg["text"]
  }
  "journal.request-run": {
    run: Arg["number"]
    kind: Arg["choice"]
    services: Arg["list"]
    count: Arg["number"]
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
  "journal.set-aura": {
    aura: Arg["number"]
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
  "journal.set-spins": {
    spins: Arg["number"]
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
  "journal.unanchored-grant": {
    from: Arg["instant"]
  }
  "journal.unlink": {
    selfService: Arg["choice"]
  }
  "journal.unlock-milestone": {
    milestone: Arg["text"]
  }
  "journal.used-authenticator": {
    hold: Arg["duration"]
  }
  "journal.web-push-subscribe": Record<string, never>
  "journal.web-push-test": {
    alert: Arg["choice"]
  }
  "journal.web-push-unsubscribe": Record<string, never>
  "report.no-build": {
    artefact: Arg["text"]
    minecraft: Arg["text"]
    loader: Arg["text"]
  }
  "report.no-database": Record<string, never>
  "report.no-source": {
    artefact: Arg["text"]
  }
  "report.not-mounted": {
    directory: Arg["text"]
  }
  "report.proxy-pack-unread": {
    error: Arg["text"]
  }
  "report.proxy-pack-without-sha-1": Record<string, never>
  "report.proxy-without-pack": Record<string, never>
  "report.release-jar-without-digest": {
    release: Arg["text"]
    jar: Arg["text"]
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
  "report.sha-1-unread": {
    file: Arg["text"]
    error: Arg["text"]
  }
  "report.size": {
    amount: Arg["number"]
    unit: Arg["choice"]
  }
  "report.velocity-ahead": {
    version: Arg["text"]
    api: Arg["text"]
  }
  "report.words": {
    text: Arg["text"]
  }
  "run.archive-pruned": {
    file: Arg["text"]
    rule: Arg["choice"]
  }
  "run.archive-saved": {
    file: Arg["text"]
    size: Arg["message"]
    took: Arg["duration"]
    verified: Arg["choice"]
  }
  "run.artefact": {
    artefact: Arg["text"]
  }
  "run.cache-freed": {
    freed: Arg["message"]
  }
  "run.exit-code": {
    code: Arg["number"]
  }
  "run.image": {
    artefact: Arg["text"]
    image: Arg["choice"]
  }
  "run.images-removed": {
    images: Arg["number"]
    freed: Arg["message"]
  }
  "run.kind": {
    kind: Arg["choice"]
  }
  "run.moved": {
    artefact: Arg["text"]
    from: Arg["text"]
    to: Arg["text"]
  }
  "run.offsite": {
    snapshot: Arg["text"]
    added: Arg["message"]
  }
  "run.outcome": {
    outcome: Arg["choice"]
  }
  "run.players": {
    moved: Arg["number"]
    from: Arg["text"]
    to: Arg["text"]
    returned: Arg["number"]
  }
  "run.reason": {
    reason: Arg["choice"]
  }
  "run.room": {
    needed: Arg["message"]
    free: Arg["message"]
  }
  "run.status": {
    status: Arg["choice"]
  }
  "run.step-kind": {
    kind: Arg["choice"]
  }
  "run.title": {
    kind: Arg["choice"]
    stage: Arg["choice"]
  }
  "run.version": {
    artefact: Arg["text"]
    version: Arg["text"]
  }
  "steward.announcements.ask": Record<string, never>
  "steward.announcements.compose": Record<string, never>
  "steward.announcements.host-channel": Record<string, never>
  "steward.announcements.no-channel": Record<string, never>
  "steward.announcements.not-posted": {
    language: Arg["text"]
  }
  "steward.announcements.posted": {
    language: Arg["text"]
  }
  "steward.announcements.send": Record<string, never>
  "steward.announcements.where": {
    languages: Arg["number"]
  }
  "steward.answer.agent-unconfigured": Record<string, never>
  "steward.answer.already-admin": Record<string, never>
  "steward.answer.answer-unreadable": Record<string, never>
  "steward.answer.aura-range": {
    most: Arg["number"]
  }
  "steward.answer.authenticator-not-offered": Record<string, never>
  "steward.answer.authenticator-there": Record<string, never>
  "steward.answer.backup-unfinished": {
    backup: Arg["text"]
  }
  "steward.answer.block-refused": {
    output: Arg["text"]
    language: Arg["text"]
    problem: Arg["text"]
  }
  "steward.answer.ceremony-elsewhere": {
    registration: Arg["choice"]
  }
  "steward.answer.ceremony-other-account": {
    registration: Arg["choice"]
  }
  "steward.answer.ceremony-stale": {
    registration: Arg["choice"]
  }
  "steward.answer.code-taken": Record<string, never>
  "steward.answer.code-wrong": Record<string, never>
  "steward.answer.confirm-restore": {
    replaces: Arg["text"]
  }
  "steward.answer.discord-answered": {
    status: Arg["number"]
  }
  "steward.answer.discord-unreachable": {
    error: Arg["text"]
  }
  "steward.answer.document-refused": {
    document: Arg["text"]
    problem: Arg["text"]
  }
  "steward.answer.draft-not-message": {
    document: Arg["text"]
  }
  "steward.answer.draft-refused": {
    document: Arg["text"]
    problem: Arg["text"]
  }
  "steward.answer.draft-undrawable": {
    document: Arg["text"]
    reason: Arg["text"]
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
  "steward.answer.image-refused": {
    mebibytes: Arg["number"]
  }
  "steward.answer.interrupted": Record<string, never>
  "steward.answer.inventory-held": {
    server: Arg["text"]
  }
  "steward.answer.inventory-unlinked": Record<string, never>
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
  "steward.answer.key-not-recent": Record<string, never>
  "steward.answer.key-refused": {
    registration: Arg["choice"]
    why: Arg["text"]
  }
  "steward.answer.last-factor": Record<string, never>
  "steward.answer.no-access-token": Record<string, never>
  "steward.answer.no-announcement": {
    announcement: Arg["number"]
  }
  "steward.answer.no-authenticator": Record<string, never>
  "steward.answer.no-backup": {
    backup: Arg["text"]
  }
  "steward.answer.no-bundle": {
    bundle: Arg["text"]
  }
  "steward.answer.no-code": Record<string, never>
  "steward.answer.no-document": {
    namespace: Arg["text"]
    name: Arg["text"]
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
  "steward.answer.secret-setting": {
    path: Arg["text"]
  }
  "steward.answer.self": Record<string, never>
  "steward.answer.server-silent": {
    server: Arg["text"]
  }
  "steward.answer.settings-changed": {
    service: Arg["text"]
    group: Arg["text"]
  }
  "steward.answer.settings-refused": {
    refusal: Arg["text"]
  }
  "steward.answer.shared-part-name": {
    name: Arg["text"]
  }
  "steward.answer.sign-in-elsewhere": Record<string, never>
  "steward.answer.sign-in-refused": {
    status: Arg["number"]
    uri: Arg["text"]
  }
  "steward.answer.sign-in-unconfigured": {
    missing: Arg["text"]
  }
  "steward.answer.spins-range": {
    most: Arg["number"]
  }
  "steward.answer.subscription-gone": Record<string, never>
  "steward.answer.too-late": Record<string, never>
  "steward.answer.too-long": {
    language: Arg["text"]
    most: Arg["number"]
  }
  "steward.answer.too-many-codes": {
    until: Arg["instant"]
  }
  "steward.answer.unknown-person": Record<string, never>
  "steward.artifact.status": {
    status: Arg["choice"]
  }
  "steward.backup-settings.no-at": Record<string, never>
  "steward.backup-settings.no-night": Record<string, never>
  "steward.backup-settings.retention-saved": Record<string, never>
  "steward.backups.all-backups": Record<string, never>
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
  "steward.backups.left": Record<string, never>
  "steward.backups.missing": Record<string, never>
  "steward.backups.next": Record<string, never>
  "steward.backups.no-archive": Record<string, never>
  "steward.backups.no-archive-yet": Record<string, never>
  "steward.backups.no-clock": Record<string, never>
  "steward.backups.no-run": Record<string, never>
  "steward.backups.none": Record<string, never>
  "steward.backups.none-finished": Record<string, never>
  "steward.backups.not-catalogued": Record<string, never>
  "steward.backups.not-tracked": Record<string, never>
  "steward.backups.nothing-to-restore": Record<string, never>
  "steward.backups.pruned": {
    rule: Arg["choice"]
  }
  "steward.backups.restore": Record<string, never>
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
  "steward.document-editor.accent": Record<string, never>
  "steward.document-editor.adaptations": Record<string, never>
  "steward.document-editor.adapted": {
    change: Arg["choice"]
  }
  "steward.document-editor.add": Record<string, never>
  "steward.document-editor.add-image": Record<string, never>
  "steward.document-editor.add-line": Record<string, never>
  "steward.document-editor.add-link": Record<string, never>
  "steward.document-editor.add-text": Record<string, never>
  "steward.document-editor.before-after": {
    state: Arg["choice"]
  }
  "steward.document-editor.beside": {
    part: Arg["choice"]
  }
  "steward.document-editor.changed": Record<string, never>
  "steward.document-editor.close": Record<string, never>
  "steward.document-editor.code": Record<string, never>
  "steward.document-editor.components": {
    count: Arg["number"]
    most: Arg["number"]
  }
  "steward.document-editor.condition": {
    condition: Arg["choice"]
  }
  "steward.document-editor.constant": Record<string, never>
  "steward.document-editor.delete-shared-part": Record<string, never>
  "steward.document-editor.detach-shared-part": Record<string, never>
  "steward.document-editor.discard": Record<string, never>
  "steward.document-editor.image": Record<string, never>
  "steward.document-editor.insert-shared-part": {
    name: Arg["text"]
  }
  "steward.document-editor.label": Record<string, never>
  "steward.document-editor.language": Record<string, never>
  "steward.document-editor.link": Record<string, never>
  "steward.document-editor.live": Record<string, never>
  "steward.document-editor.move-down": Record<string, never>
  "steward.document-editor.move-up": Record<string, never>
  "steward.document-editor.option": Record<string, never>
  "steward.document-editor.outdated": Record<string, never>
  "steward.document-editor.part": {
    part: Arg["choice"]
  }
  "steward.document-editor.placeholder": Record<string, never>
  "steward.document-editor.remove": Record<string, never>
  "steward.document-editor.required": Record<string, never>
  "steward.document-editor.restore": Record<string, never>
  "steward.document-editor.save": Record<string, never>
  "steward.document-editor.save-shared-part": Record<string, never>
  "steward.document-editor.saved": {
    document: Arg["text"]
  }
  "steward.document-editor.shared-part": {
    name: Arg["text"]
  }
  "steward.document-editor.shared-part-differs": {
    name: Arg["text"]
  }
  "steward.document-editor.shared-part-exists": {
    name: Arg["text"]
  }
  "steward.document-editor.shared-part-menu": Record<string, never>
  "steward.document-editor.shared-part-name": Record<string, never>
  "steward.document-editor.shared-part-name-rule": Record<string, never>
  "steward.document-editor.shared-part-saved": {
    name: Arg["text"]
    deleted: Arg["choice"]
  }
  "steward.document-editor.shared-part-unused": Record<string, never>
  "steward.document-editor.slot": {
    slot: Arg["text"]
  }
  "steward.document-editor.style": {
    style: Arg["choice"]
  }
  "steward.document-editor.update-shared-part": {
    name: Arg["text"]
  }
  "steward.document-editor.upload": Record<string, never>
  "steward.document-editor.version": {
    version: Arg["choice"]
  }
  "steward.document-editor.versions": Record<string, never>
  "steward.document-editor.when": {
    of: Arg["choice"]
  }
  "steward.failure.agent-silent": Record<string, never>
  "steward.failure.docker-silent": Record<string, never>
  "steward.failure.http": {
    status: Arg["number"]
  }
  "steward.failure.loading": Record<string, never>
  "steward.failure.log-lost": Record<string, never>
  "steward.failure.not-done": Record<string, never>
  "steward.failure.not-loaded": Record<string, never>
  "steward.failure.not-requested": Record<string, never>
  "steward.failure.server-failed": {
    service: Arg["text"]
  }
  "steward.failure.server-silent": {
    service: Arg["text"]
  }
  "steward.failure.try-again": Record<string, never>
  "steward.failure.unreachable": Record<string, never>
  "steward.form.cancel": Record<string, never>
  "steward.form.changed": {
    count: Arg["number"]
  }
  "steward.form.close": Record<string, never>
  "steward.form.days": Record<string, never>
  "steward.form.discard": Record<string, never>
  "steward.form.done": Record<string, never>
  "steward.form.remove": Record<string, never>
  "steward.form.remove-named": {
    name: Arg["text"]
  }
  "steward.form.removing": Record<string, never>
  "steward.form.reset": Record<string, never>
  "steward.form.save": Record<string, never>
  "steward.form.save-count": {
    count: Arg["number"]
  }
  "steward.form.saving": Record<string, never>
  "steward.form.schedule": Record<string, never>
  "steward.form.schedule-saved": Record<string, never>
  "steward.form.waiting": Record<string, never>
  "steward.form.weekday": {
    day: Arg["choice"]
  }
  "steward.game.active": Record<string, never>
  "steward.game.advancement": Record<string, never>
  "steward.game.all-milestones": Record<string, never>
  "steward.game.arena": Record<string, never>
  "steward.game.aura-of": {
    name: Arg["text"]
  }
  "steward.game.chance": Record<string, never>
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
  "steward.game.duel-types": Record<string, never>
  "steward.game.item": Record<string, never>
  "steward.game.keep": Record<string, never>
  "steward.game.loadout": Record<string, never>
  "steward.game.locked": Record<string, never>
  "steward.game.max": Record<string, never>
  "steward.game.milestone": Record<string, never>
  "steward.game.milestones": Record<string, never>
  "steward.game.min": Record<string, never>
  "steward.game.move-down": {
    name: Arg["text"]
  }
  "steward.game.move-up": {
    name: Arg["text"]
  }
  "steward.game.no-round": Record<string, never>
  "steward.game.no-track": Record<string, never>
  "steward.game.no-way-back": Record<string, never>
  "steward.game.objective": Record<string, never>
  "steward.game.platform": Record<string, never>
  "steward.game.prize": Record<string, never>
  "steward.game.progress": {
    name: Arg["text"]
  }
  "steward.game.region": Record<string, never>
  "steward.game.registered": {
    count: Arg["number"]
  }
  "steward.game.remove-ask": {
    name: Arg["text"]
  }
  "steward.game.remove-it": Record<string, never>
  "steward.game.round": Record<string, never>
  "steward.game.running": Record<string, never>
  "steward.game.spins-of": {
    name: Arg["text"]
  }
  "steward.game.start": {
    anyway: Arg["choice"]
  }
  "steward.game.start-ask": {
    anyway: Arg["choice"]
  }
  "steward.game.start-round": Record<string, never>
  "steward.game.tasks": {
    finished: Arg["number"]
    total: Arg["number"]
  }
  "steward.game.total-weight": {
    total: Arg["number"]
  }
  "steward.game.track": Record<string, never>
  "steward.game.unlock": Record<string, never>
  "steward.game.unlock-ask": {
    name: Arg["text"]
  }
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
  "steward.identity.open-person": Record<string, never>
  "steward.image.drift": {
    drift: Arg["choice"]
  }
  "steward.image.label": Record<string, never>
  "steward.image.local-jars": Record<string, never>
  "steward.image.unchecked-tip": Record<string, never>
  "steward.journal.after-hours": {
    hours: Arg["number"]
    minutes: Arg["number"]
  }
  "steward.journal.after-minutes": {
    minutes: Arg["number"]
  }
  "steward.journal.checked": {
    when: Arg["text"]
  }
  "steward.journal.days": {
    days: Arg["number"]
  }
  "steward.journal.meter": {
    subject: Arg["text"]
    value: Arg["number"]
    limit: Arg["number"]
  }
  "steward.journal.no-entry": Record<string, never>
  "steward.journal.nothing-open": Record<string, never>
  "steward.journal.older": Record<string, never>
  "steward.journal.open-now": Record<string, never>
  "steward.journal.quoted": {
    word: Arg["text"]
  }
  "steward.journal.read-as": Record<string, never>
  "steward.journal.search": Record<string, never>
  "steward.journal.search-hint": Record<string, never>
  "steward.journal.title": Record<string, never>
  "steward.journal.today": Record<string, never>
  "steward.journal.unreadable": Record<string, never>
  "steward.journal.yesterday": Record<string, never>
  "steward.keys.aborted": Record<string, never>
  "steward.keys.add": Record<string, never>
  "steward.keys.add-note": {
    domain: Arg["text"]
  }
  "steward.keys.add-title": Record<string, never>
  "steward.keys.already-registered": Record<string, never>
  "steward.keys.another-first": Record<string, never>
  "steward.keys.app-heading": Record<string, never>
  "steward.keys.app-instead": Record<string, never>
  "steward.keys.app-qr": Record<string, never>
  "steward.keys.app-scan": Record<string, never>
  "steward.keys.app-secret": Record<string, never>
  "steward.keys.app-title": Record<string, never>
  "steward.keys.back-to-overview": Record<string, never>
  "steward.keys.brand": Record<string, never>
  "steward.keys.browser-cannot": Record<string, never>
  "steward.keys.call-it": Record<string, never>
  "steward.keys.call-this-one": Record<string, never>
  "steward.keys.cancelled": Record<string, never>
  "steward.keys.checking": Record<string, never>
  "steward.keys.closed": Record<string, never>
  "steward.keys.code-label": Record<string, never>
  "steward.keys.confirm-code": Record<string, never>
  "steward.keys.did-not-work": Record<string, never>
  "steward.keys.either-factor-title": Record<string, never>
  "steward.keys.end-step-up": Record<string, never>
  "steward.keys.heading": Record<string, never>
  "steward.keys.hold-for": {
    length: Arg["duration"]
  }
  "steward.keys.hold-key": Record<string, never>
  "steward.keys.hold-title": Record<string, never>
  "steward.keys.keep-it": Record<string, never>
  "steward.keys.last-used": {
    at: Arg["instant"]
  }
  "steward.keys.longer-hold": Record<string, never>
  "steward.keys.my-iphone": Record<string, never>
  "steward.keys.my-key": Record<string, never>
  "steward.keys.my-mac": Record<string, never>
  "steward.keys.my-phone": Record<string, never>
  "steward.keys.new-name": {
    key: Arg["text"]
  }
  "steward.keys.no-answer": Record<string, never>
  "steward.keys.no-app": Record<string, never>
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
  "steward.keys.only-factor": Record<string, never>
  "steward.keys.open-directly": Record<string, never>
  "steward.keys.open-in-app": Record<string, never>
  "steward.keys.or": Record<string, never>
  "steward.keys.register": Record<string, never>
  "steward.keys.register-this": Record<string, never>
  "steward.keys.registered": {
    at: Arg["instant"]
  }
  "steward.keys.registration-incomplete": Record<string, never>
  "steward.keys.remove": {
    key: Arg["text"]
  }
  "steward.keys.remove-app": Record<string, never>
  "steward.keys.remove-app-note": Record<string, never>
  "steward.keys.remove-app-title": Record<string, never>
  "steward.keys.remove-it": Record<string, never>
  "steward.keys.remove-note": Record<string, never>
  "steward.keys.remove-title": {
    key: Arg["text"]
  }
  "steward.keys.rename": {
    key: Arg["text"]
  }
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
  "steward.keys.step-up-holds": {
    until: Arg["instant"]
  }
  "steward.keys.step-up-lasts": {
    length: Arg["duration"]
  }
  "steward.keys.step-up-title": Record<string, never>
  "steward.keys.try-again": Record<string, never>
  "steward.keys.unexplained": Record<string, never>
  "steward.keys.unsupported": Record<string, never>
  "steward.keys.use-code": Record<string, never>
  "steward.keys.use-key": Record<string, never>
  "steward.keys.waiting": Record<string, never>
  "steward.keys.with-discord": Record<string, never>
  "steward.keys.wrong-domain": Record<string, never>
  "steward.message-editor.action": Record<string, never>
  "steward.message-editor.add-case": Record<string, never>
  "steward.message-editor.add-colour": Record<string, never>
  "steward.message-editor.add-variant": Record<string, never>
  "steward.message-editor.apply": Record<string, never>
  "steward.message-editor.as-it-looks": Record<string, never>
  "steward.message-editor.bold": Record<string, never>
  "steward.message-editor.by-number": Record<string, never>
  "steward.message-editor.by-value": Record<string, never>
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
  "steward.message-editor.member": Record<string, never>
  "steward.message-editor.new-case": Record<string, never>
  "steward.message-editor.no-colour": Record<string, never>
  "steward.message-editor.obfuscated": Record<string, never>
  "steward.message-editor.open-url": Record<string, never>
  "steward.message-editor.override": Record<string, never>
  "steward.message-editor.packaged-now": Record<string, never>
  "steward.message-editor.packaged-then": Record<string, never>
  "steward.message-editor.place": {
    place: Arg["choice"]
  }
  "steward.message-editor.placeholder": Record<string, never>
  "steward.message-editor.remove": Record<string, never>
  "steward.message-editor.remove-case": Record<string, never>
  "steward.message-editor.remove-colour": Record<string, never>
  "steward.message-editor.remove-variant": Record<string, never>
  "steward.message-editor.run-command": Record<string, never>
  "steward.message-editor.send-in-discord": Record<string, never>
  "steward.message-editor.show-in-game": Record<string, never>
  "steward.message-editor.source": Record<string, never>
  "steward.message-editor.strikethrough": Record<string, never>
  "steward.message-editor.suggest-command": Record<string, never>
  "steward.message-editor.take-over": Record<string, never>
  "steward.message-editor.the-number": Record<string, never>
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
  "steward.network.not-running": Record<string, never>
  "steward.network.open": {
    service: Arg["text"]
  }
  "steward.network.players": Record<string, never>
  "steward.network.players-online": {
    count: Arg["number"]
  }
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
  "steward.operations.all-notes": Record<string, never>
  "steward.operations.all-updates": Record<string, never>
  "steward.operations.ask": {
    kind: Arg["choice"]
  }
  "steward.operations.ask-warning": {
    kind: Arg["choice"]
  }
  "steward.operations.cancel": Record<string, never>
  "steward.operations.cancelled": {
    run: Arg["number"]
  }
  "steward.operations.cancelled-note": Record<string, never>
  "steward.operations.duration": Record<string, never>
  "steward.operations.entered": {
    kind: Arg["text"]
    run: Arg["number"]
  }
  "steward.operations.failed": {
    count: Arg["number"]
  }
  "steward.operations.local-builds": {
    builds: Arg["list"]
  }
  "steward.operations.moved": {
    services: Arg["number"]
    artefacts: Arg["number"]
  }
  "steward.operations.no-change": Record<string, never>
  "steward.operations.no-earlier-than": Record<string, never>
  "steward.operations.no-report": Record<string, never>
  "steward.operations.not-a-number": Record<string, never>
  "steward.operations.not-cancelled": {
    run: Arg["number"]
  }
  "steward.operations.notes": Record<string, never>
  "steward.operations.nothing-to-do": Record<string, never>
  "steward.operations.nothing-to-do-title": Record<string, never>
  "steward.operations.nothing-written": Record<string, never>
  "steward.operations.now": Record<string, never>
  "steward.operations.outcome": Record<string, never>
  "steward.operations.report": Record<string, never>
  "steward.operations.requested-by": Record<string, never>
  "steward.operations.run": {
    id: Arg["text"]
  }
  "steward.operations.saved": {
    count: Arg["number"]
  }
  "steward.operations.saved-nothing": Record<string, never>
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
  "steward.overview.partly-unreadable": Record<string, never>
  "steward.overview.unreadable": Record<string, never>
  "steward.overview.used-of": {
    used: Arg["text"]
    total: Arg["text"]
  }
  "steward.overview.whole-journal": Record<string, never>
  "steward.payments.amount": Record<string, never>
  "steward.payments.created": {
    at: Arg["instant"]
  }
  "steward.payments.days": Record<string, never>
  "steward.payments.deadline": Record<string, never>
  "steward.payments.donation": Record<string, never>
  "steward.payments.filter": Record<string, never>
  "steward.payments.no-match": Record<string, never>
  "steward.payments.no-payment": {
    reference: Arg["text"]
  }
  "steward.payments.no-request": Record<string, never>
  "steward.payments.no-tab": Record<string, never>
  "steward.payments.not-open": {
    reference: Arg["text"]
  }
  "steward.payments.not-open-note": {
    was: Arg["text"]
  }
  "steward.payments.nothing-booked": Record<string, never>
  "steward.payments.only-overdue": Record<string, never>
  "steward.payments.open": Record<string, never>
  "steward.payments.overdue": Record<string, never>
  "steward.payments.paid": Record<string, never>
  "steward.payments.past-deadline": {
    count: Arg["number"]
  }
  "steward.payments.person": Record<string, never>
  "steward.payments.reference": Record<string, never>
  "steward.payments.requested": Record<string, never>
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
  "steward.payments.status": Record<string, never>
  "steward.payments.tab": Record<string, never>
  "steward.payments.title": Record<string, never>
  "steward.people.access": Record<string, never>
  "steward.people.actions-for": {
    name: Arg["text"]
  }
  "steward.people.active-until": {
    until: Arg["instant"]
  }
  "steward.people.admin": Record<string, never>
  "steward.people.admins": Record<string, never>
  "steward.people.armour": Record<string, never>
  "steward.people.aura": Record<string, never>
  "steward.people.aura-now": {
    aura: Arg["number"]
  }
  "steward.people.aura-set": {
    name: Arg["text"]
    aura: Arg["number"]
  }
  "steward.people.aura-title": Record<string, never>
  "steward.people.begins": {
    at: Arg["instant"]
  }
  "steward.people.below-too": {
    count: Arg["number"]
  }
  "steward.people.count": {
    count: Arg["number"]
  }
  "steward.people.counted": {
    counted: Arg["duration"]
    becoming: Arg["duration"]
    usable: Arg["choice"]
  }
  "steward.people.ender-chest": Record<string, never>
  "steward.people.exact-tip": {
    at: Arg["instant"]
  }
  "steward.people.expired": {
    at: Arg["instant"]
  }
  "steward.people.filter": Record<string, never>
  "steward.people.filter-name": Record<string, never>
  "steward.people.grant": Record<string, never>
  "steward.people.grant-access": Record<string, never>
  "steward.people.grant-title": {
    name: Arg["text"]
  }
  "steward.people.granted": Record<string, never>
  "steward.people.granted-by": {
    name: Arg["text"]
  }
  "steward.people.guild": Record<string, never>
  "steward.people.hotbar": Record<string, never>
  "steward.people.hours": Record<string, never>
  "steward.people.inventories": Record<string, never>
  "steward.people.inventory-server": Record<string, never>
  "steward.people.inventory-status": {
    status: Arg["choice"]
    server: Arg["text"]
  }
  "steward.people.language": Record<string, never>
  "steward.people.last-changed": {
    at: Arg["instant"]
  }
  "steward.people.last-seen": Record<string, never>
  "steward.people.level": {
    level: Arg["number"]
  }
  "steward.people.linked-at": {
    at: Arg["instant"]
  }
  "steward.people.main-inventory": Record<string, never>
  "steward.people.make-admin": Record<string, never>
  "steward.people.make-admin-title": {
    name: Arg["text"]
  }
  "steward.people.member": {
    state: Arg["choice"]
  }
  "steward.people.minecraft": Record<string, never>
  "steward.people.minutes": Record<string, never>
  "steward.people.mode": {
    mode: Arg["choice"]
  }
  "steward.people.most-days": {
    most: Arg["number"]
  }
  "steward.people.never": Record<string, never>
  "steward.people.never-seen": Record<string, never>
  "steward.people.next": Record<string, never>
  "steward.people.no-access": Record<string, never>
  "steward.people.no-access-tip": {
    until: Arg["instant"]
  }
  "steward.people.no-entry": Record<string, never>
  "steward.people.no-inventory": Record<string, never>
  "steward.people.no-longer-admin": Record<string, never>
  "steward.people.no-match": Record<string, never>
  "steward.people.no-pack": Record<string, never>
  "steward.people.no-pack-tip": {
    by: Arg["text"]
    at: Arg["instant"]
  }
  "steward.people.no-payment": Record<string, never>
  "steward.people.no-period": Record<string, never>
  "steward.people.nobody": Record<string, never>
  "steward.people.none-linked": Record<string, never>
  "steward.people.none-running": Record<string, never>
  "steward.people.not-linked": Record<string, never>
  "steward.people.not-linked-tip": {
    paid: Arg["choice"]
  }
  "steward.people.nothing-to-revoke": Record<string, never>
  "steward.people.nothing-to-unlink": Record<string, never>
  "steward.people.online": Record<string, never>
  "steward.people.only-linked": Record<string, never>
  "steward.people.only-unlinked": Record<string, never>
  "steward.people.over": Record<string, never>
  "steward.people.pack": {
    exempted: Arg["choice"]
  }
  "steward.people.pack-changed": {
    exempted: Arg["choice"]
  }
  "steward.people.pack-title": {
    exempted: Arg["choice"]
    name: Arg["text"]
  }
  "steward.people.periods": Record<string, never>
  "steward.people.person": Record<string, never>
  "steward.people.playtime": Record<string, never>
  "steward.people.playtime-from": {
    time: Arg["duration"]
  }
  "steward.people.playtime-set": {
    name: Arg["text"]
  }
  "steward.people.playtime-title": Record<string, never>
  "steward.people.previous": Record<string, never>
  "steward.people.range": {
    from: Arg["number"]
    to: Arg["number"]
    total: Arg["number"]
  }
  "steward.people.remove-stack": {
    item: Arg["text"]
    amount: Arg["number"]
  }
  "steward.people.remove-stack-loss": {
    server: Arg["text"]
  }
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
  "steward.people.roles": Record<string, never>
  "steward.people.root-admin": Record<string, never>
  "steward.people.running": Record<string, never>
  "steward.people.seen-at": {
    at: Arg["instant"]
  }
  "steward.people.some-admin": Record<string, never>
  "steward.people.source": {
    source: Arg["choice"]
  }
  "steward.people.source-column": Record<string, never>
  "steward.people.spins": Record<string, never>
  "steward.people.spins-now": {
    spins: Arg["number"]
  }
  "steward.people.spins-set": {
    name: Arg["text"]
    spins: Arg["number"]
  }
  "steward.people.spins-title": Record<string, never>
  "steward.people.stack": {
    item: Arg["text"]
    amount: Arg["number"]
  }
  "steward.people.stack-removed": {
    item: Arg["text"]
    amount: Arg["number"]
  }
  "steward.people.state": Record<string, never>
  "steward.people.supporter": Record<string, never>
  "steward.people.supporters": Record<string, never>
  "steward.people.thrown-out": Record<string, never>
  "steward.people.title": Record<string, never>
  "steward.people.unknown-person": Record<string, never>
  "steward.people.unlink": Record<string, never>
  "steward.people.unlink-title": Record<string, never>
  "steward.people.unlinked": Record<string, never>
  "steward.people.valid-until": {
    until: Arg["instant"]
  }
  "steward.people.window": Record<string, never>
  "steward.people.with-access": Record<string, never>
  "steward.said.channels-not-published": Record<string, never>
  "steward.said.guild-not-listed": Record<string, never>
  "steward.said.message": Record<string, never>
  "steward.said.no-guild-id": Record<string, never>
  "steward.said.not-announced": {
    language: Arg["text"]
  }
  "steward.said.setting": {
    network: Arg["choice"]
    service: Arg["text"]
    live: Arg["choice"]
  }
  "steward.said.setting-unchecked": {
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
  "steward.season.network": Record<string, never>
  "steward.season.no-date": Record<string, never>
  "steward.season.now": Record<string, never>
  "steward.season.phase": {
    phase: Arg["choice"]
  }
  "steward.season.phase-heading": Record<string, never>
  "steward.season.phase-is-now": {
    phase: Arg["text"]
  }
  "steward.season.reason": Record<string, never>
  "steward.season.reason-placeholder": Record<string, never>
  "steward.season.remove-title": {
    date: Arg["text"]
  }
  "steward.season.saved-at": {
    at: Arg["instant"]
  }
  "steward.season.smp-start": Record<string, never>
  "steward.season.smp-start-removal": Record<string, never>
  "steward.season.switch-phase": Record<string, never>
  "steward.season.switch-title": {
    phase: Arg["text"]
  }
  "steward.season.switching": Record<string, never>
  "steward.season.title": Record<string, never>
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
  "steward.service-page.exit-code": Record<string, never>
  "steward.service-page.find": Record<string, never>
  "steward.service-page.find-in": Record<string, never>
  "steward.service-page.given": Record<string, never>
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
  "steward.service-page.jar-version": {
    version: Arg["text"]
  }
  "steward.service-page.last-run": Record<string, never>
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
  "steward.service-page.recreate-service": {
    service: Arg["text"]
  }
  "steward.service-page.recreate-title": {
    service: Arg["text"]
  }
  "steward.service-page.release-version": {
    release: Arg["text"]
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
  "steward.service-page.stats": Record<string, never>
  "steward.service-page.the-jar": Record<string, never>
  "steward.service-page.uncheckable": Record<string, never>
  "steward.service-page.up-to-date": Record<string, never>
  "steward.service-page.update-available": Record<string, never>
  "steward.service-page.waiting-for-log": Record<string, never>
  "steward.service.docker-state": {
    state: Arg["text"]
  }
  "steward.service.exited": {
    code: Arg["number"]
    at: Arg["instant"]
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
  "steward.settings.applies-after-restart": Record<string, never>
  "steward.settings.applies-on-save": Record<string, never>
  "steward.settings.back-to-files": Record<string, never>
  "steward.settings.back-to-top": Record<string, never>
  "steward.settings.cannot-remove": Record<string, never>
  "steward.settings.changed": Record<string, never>
  "steward.settings.choose-one": Record<string, never>
  "steward.settings.choose-suggestion": Record<string, never>
  "steward.settings.clear-search": Record<string, never>
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
  "steward.settings.file-saved": {
    file: Arg["text"]
  }
  "steward.settings.files": Record<string, never>
  "steward.settings.filters": Record<string, never>
  "steward.settings.free-text": Record<string, never>
  "steward.settings.incomplete": {
    missing: Arg["text"]
  }
  "steward.settings.invalid-fields": {
    count: Arg["number"]
  }
  "steward.settings.keep-it": Record<string, never>
  "steward.settings.no-colour": Record<string, never>
  "steward.settings.no-entries": Record<string, never>
  "steward.settings.no-files": Record<string, never>
  "steward.settings.no-game-data": Record<string, never>
  "steward.settings.no-match": Record<string, never>
  "steward.settings.no-such-file": Record<string, never>
  "steward.settings.no-worlds": {
    service: Arg["text"]
  }
  "steward.settings.none": Record<string, never>
  "steward.settings.not-in-bundle": Record<string, never>
  "steward.settings.not-of-type": {
    expected: Arg["choice"]
  }
  "steward.settings.not-readable": Record<string, never>
  "steward.settings.not-saved": Record<string, never>
  "steward.settings.not-set": Record<string, never>
  "steward.settings.overridden": Record<string, never>
  "steward.settings.pick-colour": Record<string, never>
  "steward.settings.plugin-files": Record<string, never>
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
  "steward.settings.remove-it": Record<string, never>
  "steward.settings.reset-to-packaged": Record<string, never>
  "steward.settings.search": Record<string, never>
  "steward.settings.search-file": Record<string, never>
  "steward.settings.search-in": {
    what: Arg["text"]
  }
  "steward.settings.set": Record<string, never>
  "steward.settings.settings-saved": {
    count: Arg["number"]
  }
  "steward.settings.syntax-error": Record<string, never>
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
  "steward.shell.version": {
    version: Arg["text"]
  }
  "steward.texts.all-services": Record<string, never>
  "steward.texts.group": {
    group: Arg["choice"]
  }
  "steward.texts.none": Record<string, never>
  "steward.texts.search": Record<string, never>
  "steward.texts.service": Record<string, never>
  "steward.texts.shown-in": Record<string, never>
  "steward.updates.available": Record<string, never>
  "steward.updates.change": Record<string, never>
  "steward.updates.check-again": Record<string, never>
  "steward.updates.check-again-failed": Record<string, never>
  "steward.updates.checked": Record<string, never>
  "steward.updates.incomplete": Record<string, never>
  "steward.updates.initiated-by": Record<string, never>
  "steward.updates.kind": Record<string, never>
  "steward.updates.next": Record<string, never>
  "steward.updates.no-day": Record<string, never>
  "steward.updates.no-run": Record<string, never>
  "steward.updates.no-section": Record<string, never>
  "steward.updates.not-scheduled": Record<string, never>
  "steward.updates.nothing": Record<string, never>
  "steward.updates.nothing-to-install": Record<string, never>
  "steward.updates.plugin": Record<string, never>
  "steward.updates.resource-pack": Record<string, never>
  "steward.updates.restart-everything": Record<string, never>
  "steward.updates.result": Record<string, never>
  "steward.updates.run": Record<string, never>
  "steward.updates.runs": Record<string, never>
  "steward.updates.service": Record<string, never>
  "steward.updates.source-silent": Record<string, never>
  "steward.updates.state": Record<string, never>
  "steward.updates.status": Record<string, never>
  "steward.updates.title": Record<string, never>
  "steward.updates.update-everything": Record<string, never>
  "steward.updates.when": Record<string, never>
}
