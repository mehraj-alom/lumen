import { useCallback, useEffect, useState } from 'react'
import { api, openEventStream } from './api'
import type { AgentState, BudgetSnapshot, RunEvent } from './types'

/**
 * The spectating console.
 *
 * M0 renders the run state, the budget and the event feed. The live frame
 * surface, the plan graph, the approval queue and the takeover control are
 * M13 (`TASK.md`).
 *
 * The console never mutates run state on its own: it sends explicit control
 * requests and renders what comes back over the event stream.
 */
export function App() {
  const [runId, setRunId] = useState<string | null>(null)
  const [goal, setGoal] = useState('')
  const [state, setState] = useState<AgentState | null>(null)
  const [budget, setBudget] = useState<BudgetSnapshot | null>(null)
  const [events, setEvents] = useState<RunEvent[]>([])
  const [error, setError] = useState<string | null>(null)

  const start = useCallback(async () => {
    setError(null)
    try {
      const { runId: created } = await api.createRun({ goal })
      setRunId(created)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
    }
  }, [goal])

  useEffect(() => {
    if (runId === null) {
      return
    }
    void api.getState(runId).then(setState).catch((cause: unknown) => {
      setError(cause instanceof Error ? cause.message : String(cause))
    })
    void api.getBudget(runId).then(setBudget).catch(() => undefined)

    const dispose = openEventStream(
      runId,
      (event) => {
        setEvents((previous) => [...previous.slice(-199), event])
        if (event.type.startsWith('run.') || event.type.startsWith('step.')) {
          void api.getState(runId).then(setState).catch(() => undefined)
        }
      },
      (cause) => setError(cause instanceof Error ? cause.message : String(cause)),
    )
    return dispose
  }, [runId])

  return (
    <main style={{ padding: 24, fontFamily: 'system-ui, sans-serif' }}>
      <h1>LUMEN</h1>

      {runId === null ? (
        <section>
          <label htmlFor="goal">Goal</label>
          <input
            id="goal"
            value={goal}
            onChange={(event) => setGoal(event.target.value)}
            style={{ width: '100%', marginTop: 8 }}
          />
          <button type="button" onClick={() => void start()} disabled={goal.trim() === ''}>
            Start
          </button>
        </section>
      ) : (
        <section>
          <p>
            Run <code>{runId}</code> — {state?.phase ?? 'IDLE'} ({state?.stepIndex ?? 0}/
            {state?.stepCount ?? 0})
          </p>
          {budget !== null && (
            <p>
              RAM {budget.ramUsedMiB}/{budget.ramLimitMiB} MiB · Storage {budget.storageUsedMiB}/
              {budget.storageLimitMiB} MiB · Steps {budget.stepsUsed}/{budget.stepsHardLimit}
            </p>
          )}
          <ol>
            {events.map((event) => (
              <li key={`${event.runId}-${event.seq}`}>
                <code>{event.type}</code> {event.intent ?? event.text ?? ''}
              </li>
            ))}
          </ol>
        </section>
      )}

      {error !== null && <p role="alert">{error}</p>}
    </main>
  )
}
