package dev.lumen.core.brain

/**
 * Identity of this module. Referenced by the console's diagnostics and by
 * the module-boundary checks in `dependencyGuard`.
 */
public object ModuleInfo {
    /** The Gradle module path, for example `:core:model`. */
    public const val PATH: String = ":core:brain"

    /** The package this module owns. */
    public const val PACKAGE: String = "dev.lumen.core.brain"
}
