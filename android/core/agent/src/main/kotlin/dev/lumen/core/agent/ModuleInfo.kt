package dev.lumen.core.agent

/**
 * Identity of this module. Referenced by the console's diagnostics and by
 * the module-boundary checks in `dependencyGuard`.
 */
public object ModuleInfo {
    /** The Gradle module path, for example `:core:model`. */
    public const val PATH: String = ":core:agent"

    /** The package this module owns. */
    public const val PACKAGE: String = "dev.lumen.core.agent"
}
