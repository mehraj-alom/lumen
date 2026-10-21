package dev.lumen.core.brain

import dev.lumen.core.model.ApiError
import dev.lumen.core.model.BrainPin
import dev.lumen.core.model.ErrorCode

/**
 * Whether a local model can serve a request.
 *
 * The gate is the whole of the `AUTO` policy: a local model has to be
 * installed *and* fit inside the remaining RAM budget. The check is a
 * question rather than a fact because the budget moves.
 */
public fun interface LocalModelGate {
    /** Whether a local model is installed and fits the remaining budget. */
    public suspend fun isAvailable(): Boolean
}

/**
 * The brain router.
 *
 * The routing policy is `D2`: local when a qualifying model is installed and
 * fits the budget, otherwise cloud. The user's pin always wins over the
 * automatic route, in both directions — a `LOCAL` pin is never silently
 * downgraded to the cloud, because a pin is a statement about privacy that
 * the user made on purpose.
 */
public class BrainRouter(
    private val local: BrainPort?,
    private val cloud: BrainPort,
    private val pin: BrainPin = BrainPin.AUTO,
    private val localGate: LocalModelGate = LocalModelGate { false },
) : BrainPort {

    /** The route the router would use right now. */
    public suspend fun resolveRoute(): BrainRoute = when (pin) {
        BrainPin.LOCAL -> BrainRoute.LOCAL
        BrainPin.CLOUD -> BrainRoute.CLOUD
        BrainPin.AUTO -> if (local != null && localGate.isAvailable()) {
            BrainRoute.LOCAL
        } else {
            BrainRoute.CLOUD
        }
    }

    /** The route that served the last request. */
    public var lastRoute: BrainRoute? = null
        private set

    override val route: BrainRoute
        get() = BrainRoute.CLOUD

    override suspend fun plan(request: PlanRequest): BrainReply =
        serve { it.plan(request) }

    override suspend fun replan(request: ReplanRequest): BrainReply =
        serve { it.replan(request) }

    override suspend fun critique(request: CritiqueRequest): Critique =
        serve { it.critique(request) }

    private suspend fun <T> serve(call: suspend (BrainPort) -> T): T {
        val chosen = choose()
        lastRoute = chosen.route
        return call(chosen.port)
    }

    private suspend fun choose(): Choice = when (pin) {
        BrainPin.LOCAL -> {
            val port = local
            if (port == null) {
                throw BrainUnavailable(
                    ApiError(
                        code = ErrorCode.BRAIN_UNAVAILABLE,
                        message = "the brain is pinned to local, and no local model is installed",
                    ),
                )
            }
            Choice(port, BrainRoute.LOCAL)
        }

        BrainPin.CLOUD -> Choice(cloud, BrainRoute.CLOUD)

        BrainPin.AUTO -> {
            val port = local
            if (port != null && localGate.isAvailable()) {
                Choice(port, BrainRoute.LOCAL)
            } else {
                Choice(cloud, BrainRoute.CLOUD)
            }
        }
    }

    private data class Choice(val port: BrainPort, val route: BrainRoute)
}

/** No brain could serve the request. */
public class BrainUnavailable(
    public val error: ApiError,
) : Exception(error.message)
