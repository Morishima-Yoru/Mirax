## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues for `Morishima-Yoru/Mirax`. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical roles, each label string equal to its name. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: root `CONTEXT.md` and `docs/adr/`. See `docs/agents/domain.md`.

# Policy
## When Python
* Aimed to use Python 3.15 interpreter.
* Always use uv to manage Python environment.
* Prevent use `typing.Any` type-hint (prefer `object` or generics), except in low-level C-FFI/DLL wrapper modules.
* Use Google-style docstring for class, method, and:
    * use ``Args:``, ``Returns:``, ``Raises:`` sections when appropriate.
    * use ``:class:`Foo``` to reference other classes in docstring.
    * use ``Attributes:`` section for class attributes.
* Always use standard 4-space indentation (PEP 8).
* Standardize logging with `AppLogger` wrapping `rich` console styling and daily log file rotation (`YYYY-MM-DD.log`).
* Always ensure the code with clear type-hint.
* Let artifact be flake8 compliant. And considering run checker using uv.
* Always keep code be `snake_case` naming for normal, `PascalCase` naming for class and `UPPER_CASE` for Final.

## Naming Conventions
* Variables & functions: `snake_case` — descriptive, explicit verbs for functions.
* Classes & exceptions: `PascalCase` — nouns or noun phrases.
* Constants & `Final` values: `UPPER_CASE` — always explicitly marked with `Final[...]` type annotation.
* Private class members: `_snake_case` — single leading underscore for internal attributes.
* Name-mangled lifecycle methods: `__snake_case` — double leading underscore used in bootstrapping routines (e.g. `__construct_gui`).
* Generic type parameters: suffix with `T` or use PEP 695 `[T]` syntax (e.g. `stateT`, `PossibleBackendT`).

## Type Hints
* Use `A | B` instead of `Union[A, B]` (Python 3.10+ syntax).
* Use `A | None` instead of `Optional[A]`.
* Use built-in generic collections (`list[str]`, `dict[str, int]`) instead of `List`, `Dict` from `typing`.
* Leverage PEP 695 type alias syntax for modern projects:
    ```python
    type _OrEllipsis[T] = T | EllipsisType
    ```

## Architecture & OOP Design
* Primary considers OOP design pattern.
* Organize codebases with clean, decoupled module layers:
    * `definitions/` — interfaces, exceptions, namespace enums, type aliases.
    * `backends/` — low-level communication drivers (FTD2XX, VCP, SCPI).
    * `implements/` — concrete hardware/device implementors.
    * `controllers/` — sub-controllers (ISN, Menu, Status, MainController).
    * `managers/` — system managers (Config, Logger, Passphrase, Session).
    * `tasks/` — FSM task step handlers.
    * `services/` — external service integration (SFIS, Web APIs).
    * `view/` — GUI views and popups.
    * `core/` — bootstrap lifecycle, AppRuntime, engine cores.
* Key patterns in practice:
    * **Abstract Factory** — decouple board models from communication backends.
    * **FSM Orchestration** — Moore/Mealy state machines with picklable `FsmSnapshot`.
    * **Controller Orchestration (MVC/PAC)** — `MainController` composing sub-controllers.
    * **Hardware Strategy/Backend Abstraction** — inject `CommunicationInterface`.
    * **Application Lifecycle Bootstrapper** — `AppRuntime.bootstrap()` classmethod with `atexit` cleanup.

## Exception Handling
* Never swallow exceptions silently; log errors before re-raising.
* Place all custom exception classes in a dedicated `definitions/exceptions.py` module.
* Define a single root exception per project (e.g. `HiokiError`, `FsmError`), then subclass for specific domains.
* Preserve original traceback with `raise NewException(...) from err`.
* Use `contextlib.suppress(Exception)` only for explicit safe cleanup paths (e.g. hardware disconnect on `atexit`).

## Logging
* Adopt `AppLogger` as the standard logging wrapper.
* Use `rich.logging.RichHandler` for colored terminal output.
* Rotate log files daily with filename format `YYYY-MM-DD.log`.
* Initialize module-level loggers as: `_logger = logging.getLogger(__name__)`.

## Configuration
* Store user-editable settings in YAML files (`config.yaml`).
* Map YAML content to frozen dataclass models in `definitions/namespace.py`.
* Expose configs via a `ConfigManager` singleton parsed at startup.

# Globally
## Languaging
* Always keep docstring, annotation, and comment be English
* Always use Trad-Chinese be response language

## Programming Style
* Primary considers OOP design pattern.

## Misc
* Always use pwsh for terminal command

