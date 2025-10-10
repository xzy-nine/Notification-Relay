// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// Guard: ensure we don't create kotlinLSPProjectDeps task multiple times.
// Some external init-scripts (used by Kotlin Language Server) may add the
// same task concurrently which causes a "task already exists" failure.
// Only register the task when it does not already exist.
if (tasks.findByName("kotlinLSPProjectDeps") == null) {
    tasks.register("kotlinLSPProjectDeps")
}

// Compatibility shim for Kotlin Language Server init scripts:
// Some LSP init scripts expect an 'androidCompileClasspath' configuration to exist
// in Android projects. Newer Android Gradle Plugin versions or some module types
// may not expose that configuration, causing the LSP script task to fail.
// Create a no-op configuration with that name on projects that don't have it so
// the dependency-collection task can safely query it.
gradle.projectsEvaluated {
    rootProject.allprojects.forEach { p ->
        try {
            if (p.configurations.findByName("androidCompileClasspath") == null) {
                p.configurations.create("androidCompileClasspath")
            }
        } catch (ignored: Exception) {
            // best-effort: ignore failures creating the shim
        }
    }
}