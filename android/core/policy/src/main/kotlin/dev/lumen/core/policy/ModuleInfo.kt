package dev.lumen.core.policy

/**
 * Identity of this module. Referenced by the console's diagnostics and by
 * the module-boundary checks in `dependencyGuard`.
 */
public object ModuleInfo {
    /** The Gradle module path, for example `:core:model`. */
    public const val PATH: String = ":core:policy"

    /** The package this module owns. */
    public const val PACKAGE: String = "dev.lumen.core.policy"
}
