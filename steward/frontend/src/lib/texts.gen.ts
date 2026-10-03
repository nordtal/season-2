/** The values each of Steward's texts takes; written by `./gradlew :steward:generateApiTypes`. */
import type { Arg } from "@/lib/texts"

export type TextArgs = {
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
  "steward.run.kind": {
    kind: Arg["choice"]
  }
  "steward.run.status": {
    status: Arg["choice"]
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
}
