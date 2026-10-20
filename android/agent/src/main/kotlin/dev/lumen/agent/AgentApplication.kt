package dev.lumen.agent

import android.app.Application

/**
 * Agent-runtime application entry point (process `:agent`).
 *
 * The runtime owns the run. The console is a view over it, so this process
 * must survive the console being destroyed (ARCH.md §1, §6).
 */
class AgentApplication : Application()
