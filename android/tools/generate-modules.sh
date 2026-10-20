#!/usr/bin/env bash
# Generates the M0 module skeleton.
set -euo pipefail

ROOT=/home/opticpulse/Desktop/mikimouse_real/android
cd "$ROOT"

# --- pure Kotlin/JVM core modules -------------------------------------------
# name:package:extra-dependency-modules
PURE_MODULES=(
  "model:dev.lumen.core.model:"
  "action:dev.lumen.core.action:model"
  "grounding:dev.lumen.core.grounding:model"
  "policy:dev.lumen.core.policy:model,action"
  "quota:dev.lumen.core.quota:model"
  "sandbox:dev.lumen.core.sandbox:model"
  "protocol:dev.lumen.core.protocol:model"
  "brain:dev.lumen.core.brain:model,action,protocol"
  "agent:dev.lumen.core.agent:model,action,grounding,policy,quota,sandbox,brain,protocol"
)

for entry in "${PURE_MODULES[@]}"; do
  IFS=':' read -r name pkg extra <<< "$entry"
  dir="core/$name"
  mkdir -p "$dir/src/main/kotlin/${pkg//.//}" "$dir/src/test/kotlin/${pkg//.//}"

  {
    echo 'plugins {'
    echo '    alias(libs.plugins.kotlin.jvm)'
    echo '}'
    echo
    echo 'kotlin {'
    echo '    jvmToolchain(17)'
    echo '    explicitApi()'
    echo '}'
    echo
    echo 'dependencies {'
    echo '    implementation(libs.kotlin.stdlib)'
    echo '    implementation(libs.coroutines.core)'
    echo '    implementation(libs.serialization.json)'
    for dep in ${extra//,/ }; do
      echo "    implementation(project(\":core:$dep\"))"
    done
    echo
    echo '    testImplementation(libs.junit)'
    echo '    testImplementation(libs.truth)'
    echo '    testImplementation(libs.coroutines.test)'
    echo '    testImplementation(kotlin("test"))'
    echo '}'
  } > "$dir/build.gradle.kts"

  cat > "$dir/src/main/kotlin/${pkg//.//}/ModuleInfo.kt" <<EOF
package $pkg

/**
 * Identity of this module. Referenced by the console's diagnostics and by
 * the module-boundary checks in \`dependencyGuard\`.
 */
public object ModuleInfo {
    /** The Gradle module path, for example \`:core:model\`. */
    public const val PATH: String = ":core:$name"

    /** The package this module owns. */
    public const val PACKAGE: String = "$pkg"
}
EOF

  cat > "$dir/src/test/kotlin/${pkg//.//}/ModuleInfoTest.kt" <<EOF
package $pkg

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModuleInfoTest {
    @Test
    fun \`module path is namespaced\`() {
        assertThat(ModuleInfo.PATH).startsWith(":core:")
    }
}
EOF
done

# --- Android library modules ------------------------------------------------
ANDROID_LIBS=(
  "core/store:dev.lumen.core.store:model"
  "platform/accessibility:dev.lumen.platform.accessibility:model,action,grounding"
  "platform/capture:dev.lumen.platform.capture:model"
  "platform/virtualdisplay:dev.lumen.platform.virtualdisplay:model"
  "platform/idle:dev.lumen.platform.idle:model"
  "platform/overlay:dev.lumen.platform.overlay:model"
)

for entry in "${ANDROID_LIBS[@]}"; do
  IFS=':' read -r dir pkg extra <<< "$entry"
  mkdir -p "$dir/src/main/kotlin/${pkg//.//}" "$dir/src/test/kotlin/${pkg//.//}"
  mkdir -p "$dir/src/main"

  {
    echo 'plugins {'
    echo '    alias(libs.plugins.android.library)'
    echo '    alias(libs.plugins.kotlin.android)'
    echo '}'
    echo
    echo 'android {'
    echo '    namespace = "'"$pkg"'"'
    echo '    compileSdk = 35'
    echo
    echo '    defaultConfig {'
    echo '        minSdk = 26'
    echo '        testOptions.targetSdk = 35'
    echo '    }'
    echo
    echo '    compileOptions {'
    echo '        sourceCompatibility = JavaVersion.VERSION_17'
    echo '        targetCompatibility = JavaVersion.VERSION_17'
    echo '    }'
    echo '}'
    echo
    echo 'dependencies {'
    echo '    implementation(libs.kotlin.stdlib)'
    echo '    implementation(libs.coroutines.core)'
    echo '    implementation(libs.coroutines.android)'
    echo '    implementation(libs.androidx.core.ktx)'
    for dep in ${extra//,/ }; do
      echo "    implementation(project(\":core:$dep\"))"
    done
    echo
    echo '    testImplementation(libs.junit)'
    echo '    testImplementation(libs.truth)'
    echo '    testImplementation(libs.coroutines.test)'
    echo '}'
  } > "$dir/build.gradle.kts"

  cat > "$dir/src/main/kotlin/${pkg//.//}/ModuleInfo.kt" <<EOF
package $pkg

/** Identity of this module. */
public object ModuleInfo {
    /** The Gradle module path. */
    public const val PATH: String = ":$dir"

    /** The package this module owns. */
    public const val PACKAGE: String = "$pkg"
}
EOF
done

echo "core + platform modules generated"
