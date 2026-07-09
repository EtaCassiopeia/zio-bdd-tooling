import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group   = "zio.bdd"
version = "0.9.3"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// Defaults resolve from JetBrains' CDN / Marketplace — no local IDE install required.
// Override for local-sandbox development via gradle.properties or `-P`:
//   zioBdd.intellij.localPath=/Applications/IntelliJ IDEA.app
val localIdePath = providers.gradleProperty("zioBdd.intellij.localPath")

dependencies {
    intellijPlatform {
        if (localIdePath.isPresent) {
            local(localIdePath.get())
        } else {
            intellijIdeaCommunity("2024.3")
        }

        // Bundled JSON module — provides the JSON-schema provider EP used to
        // associate the Rift imposter schema (RiftImposterSchemaProviderFactory).
        bundledPlugin("com.intellij.modules.json")

        testFramework(TestFrameworkType.Platform)
        zipSigner()
        pluginVerifier()
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        name        = "zio-bdd"
        version     = project.version.toString()
        description = """
            IDE support for the zio-bdd Scala 3 / ZIO 2 BDD test framework.
            Owns .feature files — does not require the Gherkin plugin.
            <ul>
                <li>Syntax highlighting (keywords, tags, @flags(k=v), doc strings, tables)</li>
                <li>Customisable colour scheme — Settings → Editor → Color Scheme → zio-bdd Gherkin</li>
                <li>Go-to-definition: Cmd+Click a step → jump to its Scala source</li>
                <li>Diagnostics: missing step definitions highlighted with closest-match hints</li>
                <li>Autocomplete: step suggestions in .feature files</li>
                <li>Hover: parameter types and extractor names for matched steps</li>
                <li>Run configurations: right-click a Scenario or click the gutter ▶ icon to run it</li>
            </ul>
            Self-contained — no external plugins or compile step required.
        """.trimIndent()

        ideaVersion {
            // Matches the default target platform (2024.3 == build 243) declared above.
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }

    // Runs the same JetBrains Plugin Verifier the Marketplace runs on upload.
    // Wired into the local pre-push hook (.githooks/pre-push), not CI: verifying the
    // full IDE range downloads multiple multi-GB IDEs, so instead we check against
    // the IDE you already have installed (zero download). Failures block the push on
    // genuine binary-compatibility breakage; deprecated/experimental/internal API
    // usages are reported as warnings and do NOT fail the build.
    pluginVerification {
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.NOT_DYNAMIC,
        )
        ides {
            if (localIdePath.isPresent) {
                // Verify against a specific installed IDE — no download. The pre-push
                // hook auto-detects one and passes its path; point it at any other IDE
                // (e.g. an older release) to sweep the range one version at a time.
                local(localIdePath.get())
            } else {
                // Default: the sinceBuild floor, already cached as the compile target
                // above, so no extra download.
                //
                // We deliberately verify a single IDE per invocation. The pinned
                // IntelliJ Platform Gradle plugin (2.1.0) does not reliably verify a
                // multi-IDE `ides {}` block (only the last entry runs) and `recommended()`
                // resolves an unresolvable `2025.3` coordinate. Bump the plugin to use
                // those — see issue #28.
                ide("IC", "2024.3")
            }
        }
    }

    publishing {
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey        = providers.environmentVariable("PRIVATE_KEY")
        password          = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
}

// ── Bundle the LSP fat jar ────────────────────────────────────────────────────
//
// The fat jar is produced by `sbt lsp/assembly` and lands at
// src/main/resources/bin/zio-bdd-lsp.jar. Gradle's standard `processResources`
// task picks it up automatically; we just verify it's present and non-empty.

private val lspJar = layout.projectDirectory.file("src/main/resources/bin/zio-bdd-lsp.jar")

tasks.named("processResources") {
    doFirst {
        val f = lspJar.asFile
        check(f.exists() && f.length() > 0) {
            "LSP jar missing or empty at ${f.absolutePath}\n" +
            "Run `sbt lsp/assembly` from the zio-bdd-tooling root first."
        }
    }
}

tasks {
    withType<JavaCompile> {
        // IntelliJ Platform 2024.3 (target platform above) requires 21.
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        // Emit real Java default methods for inherited interface members instead of
        // Kotlin override stubs. Without this, implementing a platform interface (e.g.
        // ToolWindowFactory) makes the verifier flag its internal/deprecated default
        // methods as "overridden" by our class even though we never touch them.
        compilerOptions.freeCompilerArgs.add("-jvm-default=no-compatibility")
    }

    // We don't ship Java sources, so `instrumentCode` adds nothing and
    // would require pulling `java-compiler-ant-tasks` from the JetBrains CDN.
    named("instrumentCode")          { enabled = false }
    named("instrumentTestCode")      { enabled = false }
    // We don't expose searchable settings, so the IDE-spin-up step is wasted work.
    named("buildSearchableOptions")  { enabled = false }
}
