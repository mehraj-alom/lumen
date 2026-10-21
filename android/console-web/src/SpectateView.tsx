import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from './api'
import type { AgentState, ApprovalRequest, RunEvent } from './types'

/**
 * The live spectating view.
 *
 * The frames arrive on their own socket, deliberately separate from the
 * state socket: a frame upload must never head-of-line-block a step
 * approval. A dropped frame is a dropped frame, and the state stream is
 * what matters.
 */
export function SpectateView({
  runId,
  onApproval,
}: {
  runId: string
  onApproval: (approval: ApprovalRequest) => void
}) {
  const [state, setState] = useState<AgentState | null>(null)
  const [events, setEvents] = useState<RunEvent[]>([])
  const [frame, setFrame] = useState<string | null>(null)
  const frameRef = useRef<HTMLImageElement | null>(null)

  useEffect(() => {
    const base = '/v1'.replace(/^http/, 'ws')
    const stream = new WebSocket(`${base}/runs/${runId}/stream`)
    stream.onmessage = (message) => {
      const event = JSON.parse(message.data as string) as RunEvent
      if (event.type === 'approval.requested') {
        void fetch(`/v1/runs/${runId}/approvals`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(event),
        })
          .then(() => api.getState(runId))
          .then(setState)
      }
      setEvents((previous) => [...previous.slice(-99), event])
    }

    const frames = new WebSocket(`${base}/runs/${runId}/frames`)
    frames.binaryType = 'blob'
    frames.onmessage = (message) => {
      const blob = message.data as Blob
      const url = URL.createObjectURL(blob)
      setFrame((previous) => {
        if (previous) URL.revokeObjectURL(previous)
        return url
      })
    }

    void api.getState(runId).then(setState)

    return () => {
      stream.close()
      frames.close()
    }
  }, [runId])

  const takeOver = useCallback(() => {
    void api.setTakeover(runId, true)
  }, [runId])

  return (
    <section style={{ display: 'grid', gap: 12 }}>
      <div style={{ position: 'relative', aspectRatio: '9 / 16', background: '#07090F' }}>
        {frame !== null && (
          <img
            ref={frameRef}
            src={frame}
            alt="live screen"
            style={{ width: '100%', height: '100%', objectFit: 'contain' }}
          />
        )}
        <div
          style={{
            position: 'absolute',
            top: 8,
            left: 8,
            padding: '4px 10px',
            borderRadius: 999,
            background: 'rgba(12,16,24,0.7)',
            color: '#F2F5FC',
            font: '12px system-ui',
          }}
        >
          {state ? `${state.phase} · ${state.stepIndex}/${state.stepCount}` : 'connecting'}
        </div>
      </div>

      <button type="button" onClick={takeOver} disabled={state?.takeoverActive === true}>
        {state?.takeoverActive ? 'You have control' : 'Take over'}
      </button>

      <ol style={{ font: '12px ui-monospace, monospace', color: '#A9B4C8' }}>
        {events.map((event) => (
          <li key={`${event.runId}-${event.seq}`}>
            {event.type} {event.intent ?? event.text ?? ''}
          </li>
        ))}
      </ol>
    </section>
  )
}
