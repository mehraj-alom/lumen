/**
 * Wire types for the console backend, mirroring `API_SPEC.md` §2 and §6.
 *
 * These are a hand-written subset of the AIDL payload shapes. The authoritative
 * definition lives in `API_SPEC.md`; the golden test in `android/api` asserts
 * that the two agree.
 */

export type Phase =
  | 'IDLE'
  | 'PLANNING'
  | 'RUNNING'
  | 'WAITING_APPROVAL'
  | 'PAUSED'
  | 'DONE'
  | 'FAILED'
  | 'ABORTED'

export type IndicatorMode = 'EDGE_GLOW' | 'STATUS_DOT' | 'OFF'

export type IndicatorState =
  | 'IDLE'
  | 'WORKING'
  | 'AWAITING_APPROVAL'
  | 'BLOCKED'
  | 'ERROR'
  | 'ABORTING'

export type BrainRoute =
  | 'LOCAL'
  | 'CLOUD'
  | 'AUTO_RESOLVED_LOCAL'
  | 'AUTO_RESOLVED_CLOUD'

export interface StepSummary {
  readonly stepId: string
  readonly seq: number
  readonly intent: string
  readonly reason: string
  readonly riskClass: string
  readonly state: string
  readonly app: string | null
  readonly startedAtMs: number
  readonly durationMs: number | null
}

export interface AgentState {
  readonly runId: string
  readonly phase: Phase
  readonly stepIndex: number
  readonly stepCount: number
  readonly currentStep: StepSummary | null
  readonly planVersion: number
  readonly indicatorMode: IndicatorMode
  readonly indicatorState: IndicatorState
  readonly brainRoute: BrainRoute
  readonly takeoverActive: boolean
  readonly lastError: ApiError | null
}

export interface ApiError {
  readonly code: string
  readonly message: string
  readonly detail: Record<string, unknown> | null
}

export interface BudgetSnapshot {
  readonly ramUsedMiB: number
  readonly ramLimitMiB: number
  readonly ramByCategory: Readonly<Record<string, number>>
  readonly storageUsedMiB: number
  readonly storageLimitMiB: number
  readonly storageByBucket: Readonly<Record<string, number>>
  readonly stepsUsed: number
  readonly stepsSoftLimit: number
  readonly stepsHardLimit: number
  readonly elapsedMs: number
  readonly wallClockLimitMs: number
  readonly costMicros: number
  readonly costLimitMicros: number
  readonly thermalStatus: number
  readonly batteryPercent: number
}

export interface ActionPreview {
  readonly kind: string
  readonly label: string | null
  readonly text: string | null
  readonly target: string | null
}

export interface ApprovalRequest {
  readonly runId: string
  readonly approvalId: string
  readonly stepId: string
  readonly riskClass: string
  readonly title: string
  readonly detail: string
  readonly app: string
  readonly appLabel: string
  readonly consequences: readonly string[]
  readonly actionPreview: ActionPreview
  readonly expiresAtMs: number
  readonly leaseSeconds: number
}

export type RunEventType =
  | 'run.started'
  | 'run.plan_created'
  | 'run.plan_revised'
  | 'step.started'
  | 'step.observed'
  | 'step.completed'
  | 'step.failed'
  | 'step.denied'
  | 'approval.requested'
  | 'approval.resolved'
  | 'brain.route_changed'
  | 'budget.warning'
  | 'budget.exhausted'
  | 'run.paused'
  | 'run.resumed'
  | 'run.completed'
  | 'run.failed'
  | 'run.aborted'
  | 'trace.note'

export interface RunEvent {
  readonly type: RunEventType
  readonly runId: string
  readonly seq: number
  readonly tsMs: number
  readonly stepId?: string
  readonly planVersion?: number
  readonly intent?: string
  readonly reason?: string
  readonly riskClass?: string
  readonly app?: string | null
  readonly durationMs?: number
  readonly text?: string
}
