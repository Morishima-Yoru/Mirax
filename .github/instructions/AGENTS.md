## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues for `Morishima-Yoru/Mirax`. See `../docs/agents/issue-tracker.md`.

### Triage labels

Five canonical roles, each label string equal to its name. See `../docs/agents/triage-labels.md`.

### Domain docs

Single-context: `../docs/CONTEXT.md` and `../docs/adr/`. See `../docs/agents/domain.md`.

### E2E testing handoff

**MANDATORY**: When user mentions any of: `E2E`, `end-to-end`, `UiAutomator`, `adb verification`, `click-fold`, `device test`, `instrumentation test` → **IMMEDIATELY READ** `.agents/e2e-testing.md` before responding.
See `.agents/e2e-testing.md` for `adb` + `UiAutomator` verification procedures.

# Policy
## When Kotlin / Java
* Aim to use Kotlin (Java only when required for JNI/legacy).
* Use Gradle (KTS) for builds.
* Prevent use of raw `Any` (prefer strong types or generics).
* Use KDoc for classes and methods (`@param`, `@return`).
* Always use standard 4-space indentation.
* Standardize logging with Android `Log` or `Timber`. Avoid silent failure.
* Avoid `!!` (not-null assertions); prefer safe calls `?.` and elvis `?:`.
* Always keep code `camelCase` for normal, `PascalCase` for classes/interfaces, and `UPPER_SNAKE_CASE` for const vals.

## Naming Conventions
* Variables & functions: `camelCase` — descriptive, explicit verbs.
* Classes & exceptions: `PascalCase` — nouns.
* Constants: `UPPER_SNAKE_CASE` (for `const val`).
* Generic type parameters: single letter `T` or descriptive `StateT`.

## Features
* Use Kotlin Coroutines & Flows (`StateFlow`/`SharedFlow`) over plain threads/RxJava.
* Prefer read-only collections (`List<T>`, `Map<K,V>`) implicitly over mutable variants.
* Use `sealed class` / `sealed interface` for exhaustive state and error modeling.
* Use `typealias` for complex lambda signatures.

## Architecture & OOP Design
* Prefer standard Android MVVM or MVI.
* Organize codebase by clean architecture boundaries:
    * `domain/` — generic interfaces, models, exceptions.
    * `data/` — repositories, concrete hw drivers, APIs.
    * `ui/` — Views, Compose, ViewModels.
    * `di/` — Dependency injection.
    * `core/` or `common/` — base components, utilities.

## Exception Handling
* Never swallow exceptions silently. Log first.
* Group custom domain exceptions in `domain/exceptions/`.
* Preserve original traceback: `throw CustomException(msg, cause)`.
* Treat expected domain errors as data (e.g. returning `Result<T>`) rather than throwing exceptions.

## Configuration
* Read settings using `DataStore` or `SharedPreferences`.
* Map settings to Kotlin `data class` models for runtime config consumption.

# Globally
## Languaging
* Always keep docstring, annotation, and comment be English
* Always use Trad-Chinese be response language

## Programming Style
* Primary considers OOP design pattern.

## Python
* Use `uv` for Python environments, dependencies, and execution; use `uv run`, `uv add`, or `uvx` as appropriate.
* Use CPython 3.14 or newer and run `flake8` via `uv`.

## Shell Guardrails
* The `shell` tool runs Git Bash on Windows. Use Bash syntax for its command string; do not assume Linux paths or a PowerShell outer shell.
* Invoke `pwsh -NoProfile -Command '...'` or `pwsh -NoProfile -File '...'` when PowerShell is needed. Single-quote inline PowerShell code so Bash does not expand `$null`, `$_`, or other PowerShell variables.
* Use PowerShell syntax such as `$null`, `$env:VAR`, and `Select-String` only inside `pwsh`; use Bash syntax in the outer shell.
* Git Bash may convert Unix-style path arguments passed to native Windows programs. Check path handling when invoking Windows tools.

## Windows Path Guardrails (OpenCode bug workaround)
* **Always use forward slashes `/` for file paths in tool calls.**  
  → `Read("C:/Users/name/project/file.kt")` ✓ | `Read("C:\Users\name\project\file.kt")` ✗  
  Backslashes in JSON/string literals become escape sequences (`	` → tab, `\n` → newline, etc.), breaking path resolution.
* Prefer built-in tools (Read, Write, Edit, Glob, Grep) over `bash` for file operations — they accept forward-slash paths natively.
* Quote paths containing spaces; in Bash, use double quotes for variable expansions and single quotes for literals.

## Build Debugging
* **Run with `--stacktrace` option to get the stack trace** when Gradle compilation fails:
  ```powershell
  ./gradlew :app:compileDebugKotlin --stacktrace --no-daemon
  ```
* Use `--info` or `--debug` for more verbose output if needed.
