# BedwarsQOL Agent Instructions

## Project shape

- This is a Java 8 Minecraft 1.8.9 Forge mod built with Gradle.
- Shared source lives under `src/main/`.
- Lunar-specific source lives under `lunar/src/main/` and often mirrors shared source.
- Before changing mirrored files, compare both trees and keep intentional equivalents synchronized.
- Build with `./gradlew build`. Run narrower relevant checks first when available.

## Safety

- Read `git status --short` before editing.
- Preserve unrelated and pre-existing changes. Never discard or overwrite them.
- Treat generated output, game logs, credentials, API keys, and player data as sensitive.
- Do not claim Minecraft runtime behavior was verified unless the relevant client scenario was actually exercised.
- Prefer focused changes over opportunistic refactors.

## Workflow selection

Use the normal workflow for small, well-understood changes: inspect, implement, run relevant checks, and summarize.

Use the `deep-change` workflow when the user invokes it or when work involves unclear game behavior, mixins, networking, compatibility, security, architectural changes, migrations, or a bug whose cause requires substantial research. Do not impose this workflow on routine edits unless the risk justifies it.

The shared protocol is `.ai/workflows/deep-change.md`. Provider skills are thin entry points to that protocol.

## Coordination files

- `.ai/TASK.md`: user-owned task contract: problem, scope, constraints, acceptance criteria, and approval state.
- `.ai/PLAN.md`: planner-owned research and proposed implementation plan.
- `.ai/REVIEW.md`: reviewer-owned findings. Reviewers do not edit implementation code unless explicitly asked later to implement fixes.
- `.ai/HANDOFF.md`: implementer-owned concise state for the next agent or session.

Do not treat these files as automatic orchestration. Follow the ownership and stage rules in the selected workflow. Never overwrite another role's artifact without being assigned that role.

## Verification

- Record exact commands and outcomes in `.ai/HANDOFF.md` during a deep-change run.
- Distinguish passing checks, failing checks, and checks not run.
- A build alone does not prove in-game behavior. State any remaining manual verification clearly.
