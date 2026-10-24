package dev.lumen.core.brain

import dev.lumen.core.model.Action
import dev.lumen.core.model.ApiError
import dev.lumen.core.model.ErrorCode
import dev.lumen.core.model.RelativePoint
import dev.lumen.core.model.RelativeRect
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The tool the brain is given.
 *
 * The schema is the contract between the brain and the agent. The brain may
 * only emit these tools, and the agent validates every call against them
 * before anything is dispatched.
 */
public data class ToolDefinition(val name: String, val description: String, val required: List<String>, val optional: List<String>) {
    /** The property names this tool accepts. */
    public val properties: Set<String> get() = (required + optional).toSet()
}

/**
 * The tool schema.
 *
 * The definitions are generated from one table, so the schema the brain is
 * shown and the codec that reads its replies cannot drift apart.
 */
public object ToolSchema {

    /** Every tool the brain may use. */
    public val definitions: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "observe_screen",
            description = "Read the current screen: the accessibility tree, the foreground app, " +
                "and optionally a screenshot. Call this before deciding what to interact with.",
            required = emptyList(),
            optional = listOf("include_screenshot", "include_overlays", "max_nodes"),
        ),
        ToolDefinition(
            name = "tap",
            description = "Tap a relative position, or a node by its text or its view id.",
            required = emptyList(),
            optional = listOf("x", "y", "target_text", "target_id"),
        ),
        ToolDefinition(
            name = "double_tap",
            description = "Double tap a relative position.",
            required = emptyList(),
            optional = listOf("x", "y", "target_text", "target_id"),
        ),
        ToolDefinition(
            name = "long_press",
            description = "Long press a relative position.",
            required = emptyList(),
            optional = listOf("x", "y", "duration_ms"),
        ),
        ToolDefinition(
            name = "swipe",
            description = "Swipe between two relative positions.",
            required = listOf("from_x", "from_y", "to_x", "to_y"),
            optional = listOf("duration_ms"),
        ),
        ToolDefinition(
            name = "scroll",
            description = "Scroll along a direction.",
            required = listOf("direction"),
            optional = listOf("amount", "lane_x"),
        ),
        ToolDefinition(
            name = "drag",
            description = "Drag along a path of relative positions.",
            required = listOf("path"),
            optional = listOf("duration_ms", "easing"),
        ),
        ToolDefinition(
            name = "input_text",
            description = "Type text into the focused field.",
            required = listOf("text"),
            optional = emptyList(),
        ),
        ToolDefinition(
            name = "paste_text",
            description = "Paste text into the focused field.",
            required = listOf("text"),
            optional = emptyList(),
        ),
        ToolDefinition(
            name = "press_enter",
            description = "Press the enter key.",
            required = emptyList(),
            optional = emptyList(),
        ),
        ToolDefinition(
            name = "click_by_id",
            description = "Click the node with a view id.",
            required = listOf("view_id"),
            optional = emptyList(),
        ),
        ToolDefinition(
            name = "click_text",
            description = "Click the first, or the n-th, node whose label matches.",
            required = listOf("text"),
            optional = listOf("occurrence"),
        ),
        ToolDefinition(
            name = "open_app",
            description = "Open an installed application.",
            required = listOf("package"),
            optional = emptyList(),
        ),
        ToolDefinition(
            name = "open_url",
            description = "Open a url.",
            required = listOf("url"),
            optional = emptyList(),
        ),
        ToolDefinition(
            name = "list_apps",
            description = "List the launchable applications.",
            required = emptyList(),
            optional = emptyList(),
        ),
        ToolDefinition(
            name = "screenshot",
            description = "Capture a region of the screen.",
            required = emptyList(),
            optional = listOf("region"),
        ),
        ToolDefinition(
            name = "read_file",
            description = "Read a file from the sandbox.",
            required = listOf("path"),
            optional = emptyList(),
        ),
    )

    private val byName = definitions.associateBy { it.name }

    /** The definition of [name], or null. */
    public fun definition(name: String): ToolDefinition? = byName[name]

    /**
     * Validates a call.
     *
     * @return the error that stops the call, or null when the call is well
     *   formed. An unknown tool is refused: the brain cannot invent a
     *   capability.
     */
    public fun validate(name: String, arguments: JsonObject): ApiError? {
        val definition = byName[name] ?: return ApiError(
            code = ErrorCode.DENIED_BY_POLICY,
            message = "the tool '$name' is not in the schema",
            detail = mapOf("tool" to name),
        )

        val missing = definition.required.filter { !arguments.containsKey(it) }
        if (missing.isNotEmpty()) {
            return ApiError(
                code = ErrorCode.DENIED_BY_POLICY,
                message = "the tool '$name' is missing ${missing.joinToString()}",
                detail = mapOf("tool" to name, "missing" to missing.joinToString(",")),
            )
        }

        val unknown = arguments.keys.filter { it !in definition.properties }
        if (unknown.isNotEmpty()) {
            return ApiError(
                code = ErrorCode.DENIED_BY_POLICY,
                message = "the tool '$name' does not take ${unknown.joinToString()}",
                detail = mapOf("tool" to name, "unknown" to unknown.joinToString(",")),
            )
        }

        return null
    }
}

/**
 * Translates between the tool schema and the action model.
 *
 * The brain speaks in tools; the actor speaks in actions. The translation
 * happens here, once, and a tool that cannot be translated is an error
 * rather than a best guess.
 */
public object ActionCodec {

    /**
     * The tool call that performs [action].
     *
     * The codec is split in two groups so each stays readable; together they
     * cover every action, and a new action that matches neither is a codec
     * bug rather than a silent fallback.
     */
    public fun encode(action: Action): Pair<String, JsonObject> = encodeGesture(action) ?: encodeCommand(action)

    /** The tool for a movement or capture action, or null if it is a command. */
    private fun encodeGesture(action: Action): Pair<String, JsonObject>? = when (action) {
        is Action.Tap -> "tap" to json(
            "x" to action.x.fraction,
            "y" to action.y.fraction,
        )
        is Action.DoubleTap -> "double_tap" to json(
            "x" to action.x.fraction,
            "y" to action.y.fraction,
        )
        is Action.LongPress -> "long_press" to json(
            "x" to action.x.fraction,
            "y" to action.y.fraction,
            "duration_ms" to action.durationMs,
        )
        is Action.Swipe -> "swipe" to json(
            "from_x" to action.fromX.fraction,
            "from_y" to action.fromY.fraction,
            "to_x" to action.toX.fraction,
            "to_y" to action.toY.fraction,
            "duration_ms" to action.durationMs,
        )
        is Action.Scroll -> "scroll" to json(
            "direction" to action.direction.name.lowercase(),
            "amount" to action.amount,
        )
        is Action.Drag -> "drag" to json(
            "path" to JsonArray(action.path.map { JsonPrimitive(it.fraction) }),
            "duration_ms" to action.durationMs,
        )
        is Action.ObserveScreen -> "observe_screen" to json(
            "include_screenshot" to action.includeScreenshot,
            "include_overlays" to action.includeOverlays,
            "max_nodes" to action.maxNodes,
        )
        is Action.Screenshot -> "screenshot" to json(
            "region" to JsonArray(
                listOf(
                    JsonPrimitive(action.region.left),
                    JsonPrimitive(action.region.top),
                    JsonPrimitive(action.region.right),
                    JsonPrimitive(action.region.bottom),
                ),
            ),
        )
        else -> null
    }

    /** The tool for a text, click or app command. */
    private fun encodeCommand(action: Action): Pair<String, JsonObject> = when (action) {
        is Action.InputText -> "input_text" to json("text" to action.text)
        is Action.PasteText -> "paste_text" to json("text" to action.text)
        Action.PressEnter -> "press_enter" to JsonObject(emptyMap())
        is Action.ClickById -> "click_by_id" to json("view_id" to action.viewId)
        is Action.ClickText -> "click_text" to json(
            "text" to action.text,
            "occurrence" to action.occurrence,
        )
        is Action.OpenApp -> "open_app" to json("package" to action.packageName)
        is Action.OpenUrl -> "open_url" to json("url" to action.url)
        Action.ListApps -> "list_apps" to JsonObject(emptyMap())
        is Action.ReadFile -> "read_file" to json("path" to action.path)
        else -> error("the action ${action::class.simpleName} has no tool in the codec")
    }

    /**
     * The action for a tool call.
     *
     * The tool names in the two groups are disjoint, so a tool that decodes
     * in one never reaches the other and an unknown or malformed call ends
     * as null either way. The order each group matches is observable: a tool
     * that is present in one group must not be answered by the other.
     *
     * @return null when the tool is unknown or the arguments do not fit, in
     *   which case the reply is malformed and the step fails.
     */
    public fun decode(tool: String, arguments: JsonObject): Action? {
        ToolSchema.validate(tool, arguments)?.let { return null }
        return decodeGestureTool(tool, arguments) ?: decodeCommandTool(tool, arguments)
    }

    private fun decodeGestureTool(tool: String, arguments: JsonObject): Action? = when (tool) {
        "tap" -> pointTool(arguments) { x, y -> Action.Tap(x, y) }
        "double_tap" -> pointTool(arguments) { x, y -> Action.DoubleTap(x, y) }
        "long_press" -> decodeLongPress(arguments)
        "swipe" -> decodeSwipe(arguments)
        "scroll" -> decodeScroll(arguments)
        "drag" -> decodeDrag(arguments)
        "screenshot" -> decodeScreenshot(arguments)
        else -> null
    }

    private fun decodeCommandTool(tool: String, arguments: JsonObject): Action? = when (tool) {
        "input_text" -> textTool(arguments, "text", requireNonBlank = false) { Action.InputText(it) }
        "paste_text" -> textTool(arguments, "text", requireNonBlank = false) { Action.PasteText(it) }
        "press_enter" -> Action.PressEnter
        "click_by_id" -> textTool(arguments, "view_id", requireNonBlank = true) { Action.ClickById(it) }
        "click_text" -> decodeClickText(arguments)
        "open_app" -> textTool(arguments, "package", requireNonBlank = true) { Action.OpenApp(it) }
        "open_url" -> textTool(arguments, "url", requireNonBlank = true) { Action.OpenUrl(it) }
        "list_apps" -> Action.ListApps
        "read_file" -> textTool(arguments, "path", requireNonBlank = true) { Action.ReadFile(it) }
        else -> null
    }

    /** An action over the two relative-point arguments, or null if they do not fit. */
    private fun pointTool(arguments: JsonObject, build: (RelativePoint, RelativePoint) -> Action): Action? {
        val (x, y) = point(arguments) ?: return null
        return build(x, y)
    }

    private fun decodeLongPress(arguments: JsonObject): Action? {
        val (x, y) = point(arguments) ?: return null
        return Action.LongPress(x, y, arguments.long("duration_ms") ?: DEFAULT_LONG_PRESS_DURATION_MS)
    }

    private fun decodeScreenshot(arguments: JsonObject): Action? = Action.Screenshot(arguments.rect("region") ?: RelativeRect.FULL)

    /**
     * The single-text-argument tool.
     *
     * The schema carries two text rules and they are not interchangeable:
     * typed text may be whitespace but not empty; ids, packages, urls and
     * paths may be neither. [requireNonBlank] selects the rule per tool.
     */
    private fun textTool(arguments: JsonObject, key: String, requireNonBlank: Boolean, build: (String) -> Action): Action? {
        val text = arguments.string(key) ?: return null
        val accepted = if (requireNonBlank) text.isNotBlank() else text.isNotEmpty()
        return text.takeIf { accepted }?.let(build)
    }

    private fun decodeSwipe(arguments: JsonObject): Action? {
        val fromX = arguments.double("from_x") ?: return null
        val fromY = arguments.double("from_y") ?: return null
        val toX = arguments.double("to_x") ?: return null
        val toY = arguments.double("to_y") ?: return null
        return Action.Swipe(
            RelativePoint(fromX),
            RelativePoint(fromY),
            RelativePoint(toX),
            RelativePoint(toY),
            arguments.long("duration_ms") ?: DEFAULT_SWIPE_DURATION_MS,
        )
    }

    private fun decodeScroll(arguments: JsonObject): Action? {
        val direction = arguments.string("direction")?.uppercase() ?: return null
        val parsed = dev.lumen.core.model.ScrollDirection.entries
            .firstOrNull { it.name == direction } ?: return null
        return Action.Scroll(parsed, arguments.double("amount") ?: DEFAULT_SCROLL_AMOUNT)
    }

    private fun decodeDrag(arguments: JsonObject): Action? {
        val path = (arguments["path"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.doubleOrNull
        } ?: return null
        if (path.size < MIN_PATH_POINTS) return null
        return Action.Drag(
            path.map { RelativePoint(it) },
            arguments.long("duration_ms") ?: DEFAULT_DRAG_DURATION_MS,
        )
    }

    private fun decodeClickText(arguments: JsonObject): Action? {
        val text = arguments.string("text")?.takeIf { it.isNotBlank() } ?: return null
        return Action.ClickText(text, arguments.int("occurrence") ?: DEFAULT_CLICK_OCCURRENCE)
    }

    private fun point(arguments: JsonObject): Pair<RelativePoint, RelativePoint>? {
        val x = arguments.double("x") ?: return null
        val y = arguments.double("y") ?: return null
        if (x !in 0.0..1.0 || y !in 0.0..1.0) return null
        return RelativePoint(x) to RelativePoint(y)
    }

    private fun json(vararg pairs: Pair<String, Any?>): JsonObject {
        val map = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        for ((key, value) in pairs) {
            map[key] = when (value) {
                null -> kotlinx.serialization.json.JsonNull
                is String -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is Double -> JsonPrimitive(value)
                is Long -> JsonPrimitive(value)
                is Int -> JsonPrimitive(value)
                is JsonArray -> value
                else -> JsonPrimitive(value.toString())
            }
        }
        return JsonObject(map)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

    private fun JsonObject.rect(key: String): RelativeRect? {
        val values = (this[key] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.doubleOrNull
        } ?: return null
        if (values.size != REGION_EDGE_COUNT) return null
        if (values.any { it !in 0.0..1.0 }) return null
        // The array is the rectangle's edges in model order: left, top,
        // right, bottom. The size check above makes first/last exact.
        return RelativeRect(values.first(), values[1], values[2], values.last())
    }

    // The defaults a tool call gets when the brain omits the argument.
    // They mirror the defaults on the Action model itself; a drift here
    // would silently change what an under-specified call means.
    private const val DEFAULT_LONG_PRESS_DURATION_MS = 600L
    private const val DEFAULT_SWIPE_DURATION_MS = 300L
    private const val DEFAULT_DRAG_DURATION_MS = 500L
    private const val DEFAULT_SCROLL_AMOUNT = 0.5
    private const val DEFAULT_CLICK_OCCURRENCE = 0

    // A drag needs a start and an end to be a path at all, and a region
    // is exactly the four relative edges of the rectangle.
    private const val MIN_PATH_POINTS = 2
    private const val REGION_EDGE_COUNT = 4
}
