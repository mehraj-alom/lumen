import type { AgentState, ApprovalRequest, BudgetSnapshot, RunEvent } from './types'

/**
 * Client for the console backend (`API_SPEC.md` §6).
 *
 * The console is a view. It never mutates run state directly — every control
 * action is an explicit endpoint, and the resulting state arrives over the
 * event stream.
 */

const API_BASE = import.meta.env.VITE_LUMEN_API_BASE ?? '/v1'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers ?? {}),
    },
  })
  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as
      | { error?: { code?: string; message?: string } }
      | null
    const code = body?.error?.code ?? `http_${response.status}`
    const message = body?.error?.message ?? response.statusText
    throw new Error(`${code}: ${message}`)
  }
  return (await response.json()) as T
}

export interface CreateRunRequest {
  readonly goal: string
  readonly budgetOverrides?: {
    readonly stepsSoftLimit?: number
    readonly wallClockLimitMs?: number
    readonly costLimitMicros?: number
  }
}

export const api = {
  createRun: (body: CreateRunRequest) =>
    request<{ runId: string }>('/runs', { method: 'POST', body: JSON.stringify(body) }),

  getState: (runId: string) => request<AgentState>(`/runs/${runId}`),

  getBudget: (runId: string) => request<BudgetSnapshot>(`/runs/${runId}/budget`),

  getEvents: (runId: string, cursor?: string) => {
    const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''
    return request<{ items: RunEvent[]; nextCursor: string | null }>(`/runs/${runId}/events${query}`)
  },

  answerApproval: (
    runId: string,
    approvalId: string,
    decision: 'APPROVE' | 'DENY' | 'ANSWER',
    answerText?: string,
  ) =>
    request<void>(`/runs/${runId}/approvals/${approvalId}`, {
      method: 'POST',
      body: JSON.stringify({ decision, answerText }),
    }),

  setTakeover: (runId: string, takenOver: boolean) =>
    request<void>(`/runs/${runId}/takeover`, {
      method: 'POST',
      body: JSON.stringify({ takenOver }),
    }),

  abortRun: (runId: string, reason: string) =>
    request<void>(`/runs/${runId}/abort`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    }),

  setBrainPin: (runId: string, pin: 'AUTO' | 'LOCAL' | 'CLOUD') =>
    request<void>(`/runs/${runId}/brain`, {
      method: 'PUT',
      body: JSON.stringify({ pin }),
    }),
}

/**
 * Opens the event stream for a run. The backend pushes `RunEvent` frames as
 * JSON text. The socket is closed when the returned disposer is called.
 */
export function openEventStream(
  runId: string,
  onEvent: (event: RunEvent) => void,
  onError: (error: unknown) => void,
): () => void {
  const base = API_BASE.replace(/^http/, 'ws')
  const socket = new WebSocket(`${base}/runs/${runId}/stream`)

  socket.onmessage = (message: MessageEvent<string>) => {
    try {
      onEvent(JSON.parse(message.data) as RunEvent)
    } catch (error) {
      onError(error)
    }
  }
  socket.onerror = (event) => onError(event)

  return () => {
    socket.close()
  }
}

export type { ApprovalRequest }
