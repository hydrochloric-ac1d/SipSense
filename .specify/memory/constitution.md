# SipSense Constitution

SipSense is a Smart Bottle Ecosystem Android application built with Kotlin. This
constitution defines the governing development rules for the project. It applies to every
contributor and every AI agent working in this repository, and to every change — feature
work, refactors, bug fixes, build configuration, and documentation.

Where this constitution conflicts with convenience, habit, or an agent's own judgement,
this constitution wins. Where it conflicts with an approved business requirement, the
approved requirement wins and this document MUST be amended (see Governance).

This constitution does NOT create business requirements. It constrains *how* approved
requirements become working software.

## Core Principles

### I. Business-Process Authority

Approved business requirements and documented system behavior are the source of truth.

- Agents MUST NOT invent features, workflows, user roles, device behavior, or business
  rules.
- When requirements are ambiguous, the ambiguity MUST be identified and recorded as an open
  question in the specification. It MUST NOT be resolved by silent assumption.

Rationale: an assumption that reaches production becomes an undocumented business rule
nobody approved.

### II. Android/Kotlin First

The application MUST use Kotlin and follow modern Android development practices.

- Presentation, domain/business logic, data, and IoT/device integration MUST be separated.
- Business rules MUST NOT live in an Activity or Fragment. Device transport details MUST
  NOT leak into presentation code.
- Layers SHOULD be introduced as features require them; the whole app MUST NOT be
  restructured to satisfy a diagram.

Rationale: logic reachable only through an Android framework class cannot be tested without
a device, which directly blocks Principle VII.

### III. IoT Reliability

IoT communication MUST be treated as unreliable.

- Connection loss, unavailable devices, synchronization failures, duplicate events, invalid
  device data, and reconnection MUST each have defined, safe behavior.
- Code MUST NOT assume a smart bottle is continuously connected.
- Every device interaction MUST define what happens when the device is absent, stops
  responding mid-operation, or delivers the same event twice.

Rationale: a happy-path-only device integration fails in exactly the conditions users hit
most.

### IV. Data Integrity

Hydration records, targets, synchronization state, and device-related data MUST have
clearly defined ownership and persistence behavior: what writes it, where it lives, and how
long it survives.

- Duplicate hydration records caused by repeated synchronization or replayed device events
  MUST be prevented. Device events MUST carry a stable identity so replays are recognized
  rather than re-recorded.
- Incoming device data MUST be validated before it is stored or used. Implausible readings
  MUST be rejected or quarantined, not persisted.

### V. Offline-Aware Design

Core hydration tracking SHOULD remain usable when the IoT device or network is temporarily
unavailable, where permitted by the requirements.

- Synchronization MUST reconcile data rather than blindly overwrite valid local data.
- "Last write wins" is a decision, not a default. If a feature needs a conflict rule and
  the requirements do not supply one, that is an ambiguity to raise under Principle I.

### VI. Security and Privacy

User account information, hydration records, device identifiers, and authentication
information MUST be protected.

- Secrets, API keys, tokens, and credentials MUST NOT appear in source code. Configuration
  carrying credentials MUST stay out of version control.
- Permissions and device access MUST follow least privilege: request a runtime permission
  only where a feature genuinely needs it, at the point it is needed, and handle denial
  without crashing.

### VII. Testing

Every implemented feature MUST have appropriate validation and testing.

- Normal flows MUST be tested, and so MUST failure conditions — especially IoT connection
  and synchronization behavior.
- A feature MUST NOT be considered complete merely because the application compiles.
- Failure-path tests are part of the feature, not a follow-up.

### VIII. Maintainability

Simple, readable, modular Kotlin is preferred over unnecessary complexity.

- Existing project architecture and components MUST be reused where appropriate.
- Duplicate implementations MUST be avoided.
- Adding a dependency requires a stated reason.

### IX. Spec-Driven Development

Work proceeds in order, and each stage is answerable to the previous one.

1. **Specify** — features MUST be specified before implementation, capturing approved
   requirements, acceptance criteria, and open questions.
2. **Plan** — plans MUST respect the approved specification and the existing project
   architecture.
3. **Tasks** — tasks MUST be actionable and traceable to requirements.
4. **Implement** — build to the plan.
5. **Verify** — the implementation MUST be verified against the specification, not against
   the implementer's memory of it.

Skipping to implementation is a process violation even when the resulting code works.

### X. Change Control

- Unrelated functionality MUST NOT be modified while implementing a feature. Drive-by
  renames, reformatting, and opportunistic refactors do not belong in a feature change.
- Before architectural changes, the existing implementation MUST be inspected and the
  reason for the change stated.
- Existing working behavior MUST be preserved unless an approved requirement explicitly
  changes it.

### XI. Definition of Done

A change is done when ALL of the following hold:

- Requirements are implemented.
- Relevant tests pass.
- Existing functionality has not been unintentionally broken.
- IoT failure cases relevant to the feature have been considered.
- The implementation matches the approved specification and plan.
- Convergence/verification reports no unresolved critical issues.

Partial completion MUST be reported as partial completion, with the unmet items named.

## Product Scope

SipSense is designed to help users:

- Track daily water/hydration intake.
- Set and monitor personal hydration targets.
- View hydration progress and history.
- Connect and synchronize with IoT smart bottle devices.
- Receive bottle/device-related information and hydration reminders, where supported by the
  approved requirements.

This list is the outer boundary of the product. Anything outside it is out of scope until an
approved requirement brings it in scope.

## Development Workflow

`/automate-process` is the primary development workflow; its definition lives in
`.cursor/commands/automate-process.md`. It drives the Spec Kit commands
(`/speckit-specify` → `/speckit-plan` → `/speckit-tasks` → `/speckit-implement` →
`/speckit-converge`) rather than replacing them, and it enforces the manual-testing
boundary below.

Automated verification covers build checks, unit tests, available integration tests, static
analysis and lint, specification consistency checks, code inspection, and convergence
checks.

Manual verification is required — and MUST be requested rather than simulated — for
anything needing physical or real-device interaction: the actual smart bottle, Bluetooth
pairing, real connection and reconnection, sensor behavior, physical water intake events,
Android permission dialogs, real-device UI behavior, and end-to-end user experience.
Completion of manual testing MUST NOT be claimed by an agent.

## Current Architectural Baseline

Recorded so Principle X can be applied against reality rather than assumption. This section
describes the code as it exists; it is NOT a statement of requirements. It MUST be kept
accurate when the architecture changes. The graded detail behind this summary — including
open questions blocked on missing requirements — lives in
`.specify/memory/baseline-analysis.md`, which MUST be read before planning a feature.

- Kotlin Android app; namespace and `applicationId` `com.sipsense.app`; `minSdk` 26,
  `compileSdk`/`targetSdk` 35, Java/Kotlin target 17; Gradle 8.9 with Android Gradle Plugin
  8.6.0. Single module `app`, flat package `com.sipsense.app` plus `.model`.
- UI is Views plus XML (`ConstraintLayout`, Material Components), not Compose.
- Screens: `LoginActivity`, `RegisterActivity`, and `MainActivity` hosting
  `DashboardFragment`, `HistoryFragment`, `DevicesFragment`, and `ProfileFragment` behind a
  bottom navigation bar. Dashboard, History, and Devices are all bare placeholders — each
  inflates a layout containing only a title `TextView`.
- Firebase Authentication (email/password) and Firebase Realtime Database are the current
  backend. `UserProfile` (`fullName`, `email`, `hydrationTarget`, `createdAt`) is persisted
  at `/users/{uid}`. `app/google-services.json` is intentionally untracked and is currently
  absent from the working tree.
- `SharedPreferences` (`SipSensePrefs`) stores the "Remember me" email on the login screen.
- No architecture layers beyond UI: no ViewModel, repository, domain, or DI. All behavior
  lives in the four Android framework classes.
- No IoT/device integration exists, and no hydration-record model exists. The header
  connection badge is hardcoded to "connected". Only `INTERNET`,
  `ACCESS_NETWORK_STATE`, and `READ_GSERVICES` are declared, all via SDK manifest merge; no
  Bluetooth, location, or notification permissions.
- No automated test sources and no test dependencies. The Gradle wrapper is committed and
  working (Gradle 8.9), so command-line builds and lint can run; both still require a
  developer-supplied `app/google-services.json`, documented in README step 3.
- These gaps MUST be closed through specified features under Principles II, III, and VII,
  never as drive-by changes (Principle X).

## Governance

- **Precedence**: approved business requirements > this constitution > approved
  specifications > approved plans > existing project architecture > existing source-code
  behavior > agent assumptions. When two sources conflict, the conflict MUST be named and
  resolved using the highest-authority source.
- **Amendment**: amendments require an explicit, reviewed change stating what is changing
  and why. Amendments MUST NOT happen as a side effect of feature work (Principle X). Use
  `/speckit-constitution` to amend.
- **Versioning**: semantic. MAJOR for a removed or redefined principle, MINOR for a new
  principle or materially expanded guidance, PATCH for clarifications that do not change
  meaning.
- **Compliance**: specifications, plans, the `Constitution Check` gate in plans, reviews,
  and convergence MUST check against these principles by name. A violation is raised, not
  worked around.

**Version**: 1.0.1 | **Ratified**: 2026-09-30 | **Last Amended**: 2026-09-30
