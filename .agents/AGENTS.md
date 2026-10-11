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

## Misc (PowerShell Guardrails)
* Always use Windows PowerShell (`pwsh`) for terminal commands. DO NOT assume a Linux Bash environment.
* NEVER use `ls -la` (crashes due to unrecognised `-la` parameter). Use `ls` or `Get-ChildItem`.
* NEVER use `grep`. Use `Select-String` (e.g., `| Select-String "pattern"`).
* NEVER use `> /dev/null` or `2> /dev/null`. It creates a literal `C:\dev\null` file and crashes. Use `> $null` or `2> $null` instead.
* NEVER use `export VAR=VALUE`. Use `$env:VAR='VALUE'` to set environment variables.
* NEVER use `touch file`. Use `New-Item -ItemType File -Path file` instead.
* NEVER use CMD-style flags (`/s`, `/b`, `/r`, etc.) with `dir`/`Get-ChildItem`/`ls`. PowerShell uses `-Recurse`, `-Name`, `-File`/`-Directory` parameters instead. Example: `Get-ChildItem -Recurse -Name -Filter "*.dll"` not `dir /s /b *.dll`.

## Windows Path Guardrails (OpenCode bug workaround)
* **Always use forward slashes `/` for file paths in tool calls.**  
  → `Read("C:/Users/name/project/file.kt")` ✓ | `Read("C:\Users\name\project\file.kt")` ✗  
  Backslashes in JSON/string literals become escape sequences (`	` → tab, `\n` → newline, etc.), breaking path resolution.
* Prefer built-in tools (Read, Write, Edit, Glob, Grep) over `bash` for file operations — they accept forward-slash paths natively.
* If you must use `bash`, wrap paths in single quotes: `'C:/Users/name/project/file.kt'`.

## Build Debugging
* **Run with `--stacktrace` option to get the stack trace** when Gradle compilation fails:
  ```powershell
  ./gradlew :app:compileDebugKotlin --stacktrace --no-daemon
  ```
* Use `--info` or `--debug` for more verbose output if needed.