// LUMEN — root build.
//
// Module boundary rules (AGENTS.md §3, CODING_STANDARDS.md §10):
//   - `core/*` is pure Kotlin/JVM. It may not depend on android.*, androidx.*,
//     or any module outside `core/`.
//   - Every third-party dependency must be listed in `gradle/approved-dependencies.txt`.
//   - Every dependency in `gradle/pending-dependencies.txt` is BLOCKED until the
//     user explicitly approves it (TASK.md Appendix).

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

val lumenRoot = rootProject.layout.projectDirectory.dir("..").asFile

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    ktlint {
        version.set(rootProject.libs.versions.ktlint.get())
        android.set(true)
        ignoreFailures.set(false)
        filter {
            exclude { it.file.path.contains("${File.separator}build${File.separator}") }
        }
    }

    detekt {
        buildUponDefaultConfig.set(true)
        allRules.set(false)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        baseline.set(rootProject.file("config/detekt/baseline.xml"))
        parallel.set(true)
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll("-Xjvm-default=all")
        }
    }
}

// ---------------------------------------------------------------------------
// dependencyGuard
//
// Fails the build when:
//   1. A `core/*` module declares an Android or cross-module dependency.
//   2. A declared dependency is not in the approved ledger.
//   3. A declared dependency is in the pending ledger (awaiting user approval).
// ---------------------------------------------------------------------------

data class DeclaredDependency(
    val module: String,
    val group: String,
    val artifact: String,
    val coordinate: String,
)

/**
 * Resolves the declared dependencies of the whole project.
 *
 * Coordinates live in the version catalog (`gradle/libs.versions.toml`), not in
 * the build files, so the scan is: catalog alias -> accessor (`libs.kotlin.stdlib`)
 * -> the build files that use that accessor. Inline coordinates
 * (`implementation("group:artifact")`) are picked up too.
 */
fun resolveDeclaredDependencies(projectDir: File): List<DeclaredDependency> {
    val catalogFile = File(projectDir, "gradle/libs.versions.toml")
    val catalog = if (catalogFile.exists()) catalogFile.readText() else ""
    val aliasRegex = Regex("""([A-Za-z0-9._-]+)\s*=\s*\{[^}]*?module\s*=\s*"([A-Za-z0-9_.]+):([A-Za-z0-9_.-]+)""", RegexOption.DOT_MATCHES_ALL)
    val accessorToCoordinate = aliasRegex.findAll(catalog)
        .associate { match ->
            val accessor = "libs." + match.groupValues[1].replace("-", ".")
            accessor to (match.groupValues[2] to match.groupValues[3])
        }

    val accessorRegex = Regex("""\blibs\.([A-Za-z0-9_.]+)""")
    val inlineRegex = Regex("""["']([A-Za-z0-9_.]+):([A-Za-z0-9_.-]+)(?::[A-Za-z0-9_.-]+)?["']""")
    val blockCommentRegex = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    val lineCommentRegex = Regex("""//[^\n]*""")

    val declared = mutableListOf<DeclaredDependency>()
    projectDir.walkTopDown()
        .filter { it.isFile && it.name == "build.gradle.kts" }
        .forEach { file ->
            val module = file.parentFile.relativeTo(projectDir).path.replace(File.separatorChar, '/')
            // Comments are stripped so that prose in a KDoc cannot be read as a
            // dependency declaration.
            val text = lineCommentRegex.replace(blockCommentRegex.replace(file.readText(), " "), " ")

            accessorRegex.findAll(text).forEach { match ->
                val accessor = "libs." + match.groupValues[1]
                val coordinate = accessorToCoordinate[accessor] ?: return@forEach
                declared += DeclaredDependency(module, coordinate.first, coordinate.second, "${coordinate.first}:${coordinate.second}")
            }

            inlineRegex.findAll(text).forEach { match ->
                val group = match.groupValues[1]
                val artifact = match.groupValues[2]
                // Skip plugin ids and version-catalog style strings.
                if (group.startsWith("com.android.tools.build")) return@forEach
                declared += DeclaredDependency(module, group, artifact, "$group:$artifact")
            }
        }
    return declared.distinctBy { it.module + "|" + it.coordinate }
}

val dependencyGuard by tasks.registering {
    group = "verification"
    description = "Enforces the core module boundary and the dependency approval ledger."

    val projectDir = rootDir
    val approved = rootProject.file("gradle/approved-dependencies.txt")
    val pending = rootProject.file("gradle/pending-dependencies.txt")
    val specLedger = rootProject.file("../TECHSTACK.md")

    doLast {
        val declared = resolveDeclaredDependencies(projectDir)

        val approvedPrefixes = approved.readLines()
            .map { it.substringBefore("#").trim() }
            .filter { it.isNotEmpty() }
        val pendingPrefixes = pending.readLines()
            .map { it.substringBefore("#").trim() }
            .filter { it.isNotEmpty() }

        val violations = mutableListOf<String>()

        // 1. core module boundary.
        //    ADR 0002: `:core:store` is the single exception, because Room is
        //    an Android library. It may depend on Room and DataStore only.
        val coreStoreException = setOf("androidx.room", "androidx.datastore")
        declared.filter { it.module.startsWith("core/") }
            .forEach { dep ->
                val banned = dep.group.startsWith("androidx.") ||
                    dep.group.startsWith("com.android.") ||
                    dep.group.startsWith("com.google.android.") ||
                    dep.group == "android"
                if (banned) {
                    val isStore = dep.module.startsWith("core/store")
                    val isAllowedException = isStore && coreStoreException.any { dep.group == it }
                    if (!isAllowedException) {
                        violations += "core boundary: ${dep.module} declares '${dep.coordinate}' — core/* must be pure Kotlin/JVM (ADR 0002 exempts :core:store for androidx.room and androidx.datastore only)"
                    }
                }
            }

        // 2. pending ledger (awaiting explicit user approval)
        declared.forEach { dep ->
            if (pendingPrefixes.any { dep.group == it || dep.group.startsWith("$it.") }) {
                violations += "pending approval: ${dep.module} declares '${dep.coordinate}' — blocked until the user approves it (TASK.md Appendix)"
            }
        }

        // 3. approved ledger
        declared.forEach { dep ->
            val ok = approvedPrefixes.any { dep.group == it || dep.group.startsWith("$it.") }
            if (!ok) {
                violations += "unapproved dependency: ${dep.module} declares '${dep.coordinate}' — add it to gradle/approved-dependencies.txt and to the TECHSTACK.md §10 ledger"
            }
        }

        // 4. the spec ledger must exist
        if (!specLedger.exists()) {
            violations += "missing ${specLedger.path} — the TECHSTACK.md dependency ledger is the contract"
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("dependencyGuard failed with ${violations.size} violation(s):")
                    violations.sorted().forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("See AGENTS.md §4 and TECHSTACK.md §10. A new dependency requires explicit user approval.")
                },
            )
        }
        logger.lifecycle("dependencyGuard: ${declared.size} declared dependencies, all approved.")
    }
}

// ---------------------------------------------------------------------------
// dependencyLedger — every declared dependency must appear in TECHSTACK.md §10
// ---------------------------------------------------------------------------

val dependencyLedger by tasks.registering {
    group = "verification"
    description = "Checks that every declared dependency is documented in the TECHSTACK.md ledger."

    val projectDir = rootDir
    val specLedger = rootProject.file("../TECHSTACK.md")

    doLast {
        if (!specLedger.exists()) {
            throw GradleException("TECHSTACK.md is missing; the dependency ledger cannot be verified.")
        }
        // A dependency is documented when a line of the ledger mentions both
        // its group and its artifact. The ledger tables are column-separated,
        // so a line-level match is exact enough and keeps the table readable.
        val specLines = specLedger.readLines().map { it.lowercase() }
        val missing = mutableListOf<String>()

        resolveDeclaredDependencies(projectDir).forEach { dep ->
            val group = dep.group.lowercase()
            val artifact = dep.artifact.lowercase()
            val documented = specLines.any { line ->
                line.contains(group) && line.contains(artifact)
            }
            if (!documented) {
                missing += "${dep.module}: '$group:$artifact' is not in the TECHSTACK.md §10 ledger"
            }
        }

        if (missing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("dependencyLedger failed — ${missing.size} undocumented dependency/dependencies:")
                    missing.sorted().forEach { appendLine("  - $it") }
                    appendLine("Add each to TECHSTACK.md §10 and record the approval state.")
                },
            )
        }
        logger.lifecycle("dependencyLedger: every declared dependency is documented.")
    }
}
