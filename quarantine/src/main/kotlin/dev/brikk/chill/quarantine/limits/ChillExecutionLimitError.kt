package dev.brikk.chill.quarantine.limits

/**
 * Thrown from instrumented code when an execution limit is exhausted. An [Error] rather than an
 * exception so `catch (e: Exception)` in script code cannot swallow it; a `catch (t: Throwable)`
 * can, but the budget stays exhausted and the next loop iteration throws again.
 */
class ChillExecutionLimitError(message: String) : Error(message)
