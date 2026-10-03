/** The values each of Steward's texts takes; written by `./gradlew :steward:generateApiTypes`. */
import type { Arg } from "@/lib/texts"

export type TextArgs = {
  "alert.admin-not-kept": Record<string, never>
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
  "alert.donor-not-given": {
    person: Arg["mention"]
  }
  "alert.dump-matters": Record<string, never>
  "alert.failed-for": {
    person: Arg["mention"]
    error: Arg["text"]
  }
  "alert.health-fails": Record<string, never>
  "alert.level": {
    level: Arg["choice"]
  }
  "alert.link-failed": Record<string, never>
  "alert.link-refused": Record<string, never>
  "alert.memory": {
    percent: Arg["number"]
  }
  "alert.no-archive": Record<string, never>
  "alert.no-backup": Record<string, never>
  "alert.no-dump": Record<string, never>
  "alert.no-limit": Record<string, never>
  "alert.no-services": Record<string, never>
  "alert.no-such-role": {
    id: Arg["text"]
  }
  "alert.no-tier": {
    payment: Arg["text"]
    amount: Arg["money"]
    reference: Arg["text"]
  }
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
  "alert.reconcile-idle": Record<string, never>
  "alert.refused": {
    reference: Arg["text"]
    error: Arg["text"]
  }
  "alert.role-missing": {
    role: Arg["choice"]
  }
  "alert.role-not-changed": {
    role: Arg["choice"]
    given: Arg["choice"]
  }
  "alert.roles-not-kept": Record<string, never>
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
  "steward.answer.already-admin": Record<string, never>
  "steward.answer.bot-token-refused": Record<string, never>
  "steward.answer.ceremony-elsewhere": {
    registration: Arg["choice"]
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
  "steward.answer.grants-per-hour": {
    count: Arg["number"]
  }
  "steward.answer.guild-unreadable": Record<string, never>
  "steward.answer.interrupted": Record<string, never>
  "steward.answer.key-first": Record<string, never>
  "steward.answer.key-not-held": Record<string, never>
  "steward.answer.key-not-recent": {
    within: Arg["duration"]
  }
  "steward.answer.no-access-token": Record<string, never>
  "steward.answer.no-announcement": {
    announcement: Arg["number"]
  }
  "steward.answer.no-bundle": {
    bundle: Arg["text"]
  }
  "steward.answer.no-code": Record<string, never>
  "steward.answer.no-database": {
    kept: Arg["choice"]
  }
  "steward.answer.no-key": Record<string, never>
  "steward.answer.no-request": {
    request: Arg["text"]
  }
  "steward.answer.no-sources": Record<string, never>
  "steward.answer.no-such-key": Record<string, never>
  "steward.answer.not-a-member": Record<string, never>
  "steward.answer.not-admin": Record<string, never>
  "steward.answer.not-an-admin": {
    name: Arg["text"]
  }
  "steward.answer.not-below": Record<string, never>
  "steward.answer.not-in-guild": Record<string, never>
  "steward.answer.not-json": Record<string, never>
  "steward.answer.rate-limited": Record<string, never>
  "steward.answer.self": Record<string, never>
  "steward.answer.sign-in-elsewhere": Record<string, never>
  "steward.answer.sign-in-refused": {
    status: Arg["number"]
    uri: Arg["text"]
  }
  "steward.answer.sign-in-unconfigured": {
    missing: Arg["text"]
  }
  "steward.answer.too-late": Record<string, never>
  "steward.answer.unknown-guild": Record<string, never>
  "steward.answer.unknown-person": Record<string, never>
  "steward.artifact.status": {
    status: Arg["choice"]
  }
  "steward.artifact.status-tip": {
    status: Arg["choice"]
  }
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
  "steward.said.announced": {
    posted: Arg["choice"]
    language: Arg["text"]
  }
  "steward.said.guild-not-listed": Record<string, never>
  "steward.said.message": Record<string, never>
  "steward.said.no-bot-token": Record<string, never>
  "steward.said.no-guild-id": Record<string, never>
  "steward.said.setting": {
    network: Arg["choice"]
    service: Arg["text"]
    live: Arg["choice"]
  }
  "steward.said.words": {
    text: Arg["text"]
  }
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
  "steward.updates.available": Record<string, never>
  "steward.updates.change": Record<string, never>
  "steward.updates.changed": {
    count: Arg["number"]
  }
  "steward.updates.changed-meanwhile": Record<string, never>
  "steward.updates.check-again": Record<string, never>
  "steward.updates.check-again-failed": Record<string, never>
  "steward.updates.check-again-tip": Record<string, never>
  "steward.updates.checked": Record<string, never>
  "steward.updates.days": Record<string, never>
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
  "steward.updates.save": Record<string, never>
  "steward.updates.schedule": Record<string, never>
  "steward.updates.schedule-note": Record<string, never>
  "steward.updates.schedule-saved": Record<string, never>
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
