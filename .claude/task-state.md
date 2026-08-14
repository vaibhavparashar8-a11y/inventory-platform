# Claude Task State

Persistent checkpoint for long-running Claude Code work on this repository.

Two writers share this file. The runner owns the mechanical sections (git
commit, branch, working tree, interruption reason, timestamps) and rewrites only
those. Claude owns the narrative sections. Keep every `##` heading intact —
section bodies are replaced by heading name.

The repository is the source of truth. If this file and git disagree, git wins
and this file gets corrected.

## Project

ClothingInventory — local-first inventory, sales and profitability platform.

## Current Phase

Phase 1 — Catalog, stock, and channel scaffolding (BUILD_PROMPT.md §8).

## Phase Status

Phase 1 = IN PROGRESS

Set to `Phase 1 = COMPLETE` only after the Phase 1 requirements and the
Definition of Done in the guide are all met and verified: implementation done,
required tests written and actually passing, `./mvnw verify` green, docs
updated. The runner reads this section literally.

## Original Task

Implement Phase 1 as defined in `BUILD_PROMPT.md` §8 and `docs/DEVELOPER_GUIDE.md`:

- `catalog-service`: Category, Item, Variant, Firm CRUD plus pack manifest
  loading and validation
- `stock-service`: ledger, balances, reservations with TTL, idempotency,
  `rebuildBalances()` and the invariant test
- Concurrency proof: N parallel reservations against limited stock, exactly the
  right number succeed, no negative balance; plus a multi-line ordered-locking
  test proving no deadlock
- `channel-service` skeleton: `ChannelConnector` SPI, `ChannelCredential`
  storage, OAuth callback endpoint, token refresh scheduler, and a stub
  connector proving the flow end to end
- React shell: layout, routing, generated API client, catalog screens, Firm
  Connections tab
- Tag `v0.2.0-catalog-stock`

Phase 0 is complete and pushed. It must not be modified, reverted, squashed or
rewritten.

## Developer Guide

This repository has no root `developer-guide.md`. The guide is:

1. `docs/DEVELOPER_GUIDE.md` — primary deep reference (schema, invariants, data flows)
2. `BUILD_PROMPT.md` — the brief, including the phased build plan in §8
3. `CLAUDE.md` — working rules, conventions and mandatory workflow

Configured in `.claude/config.yaml` under `guide.files`.

## Phase 1 Baseline

Git commit:
b510165b3d8f4d822170bc13a329bf889676b8bc

Git branch:
feature/1-catalog-stock (created from the above commit)

Working tree at baseline:
modified — untracked tooling only (`.claude/`, `scripts/`, empty `PHASE1.md`)

Phase 0:
completed and pushed (merged to `main`, tag `v0.1.0-foundation`)

Phase 1:
not started

## Baseline Git Commit

b510165b3d8f4d822170bc13a329bf889676b8bc

## Current Git Commit

b510165b3d8f4d822170bc13a329bf889676b8bc

## Current Branch

chore/claude-task-runner

## Working Tree Status

modified

```text
M .gitignore
?? .claude/
?? .vscode/
?? PHASE1.md
?? scripts/
```

## Completed Work

- Phase 0, in full — foundation, contracts, platform-common, gateway, two
  skeleton services, composite launcher, tracing, migrations, CI. Merged and
  pushed; see `CHANGELOG.md` and the git history up to b510165.
- Auto-resume task runner (this tooling). Not part of Phase 0 and not part of
  Phase 1's deliverables.

## Current Work

Nothing in flight. Phase 1 has not started.

## Remaining Work

To be derived from the guide's Phase 1 section on the first run and recorded
here as a task list, then kept current as items complete.

## Files Created

- `.claude/config.yaml`, `.claude/task-state.md`, `.claude/prompts/*.md`
- `scripts/claude-task-runner.ps1`, `scripts/claude-runner/*.ps1`, `scripts/claude-task.cmd`
- `.vscode/tasks.json`
- `docs/CLAUDE_TASK_RUNNER.md`

## Files Modified

- `.gitignore` — un-ignore `.vscode/tasks.json` so the shared task is versioned
- `docs/DEVELOPER_GUIDE.md` — tooling section and known-issues entries

## Tests Run

Runner tooling, 2026-08-14, all actually executed:

- `-SelfTest` — classifier over canned transcripts (7 checks)
- `-Status` — baseline, checkpoint and git reporting
- `-DryRun` — prompt assembly and the exact Claude command line
- `-SimulateLimit` — full pause → checkpoint → wait → fresh session → continue
- `-Probe` — real Claude process launched, one turn, replied `RUNNER OK`

## Tests Passed

All of the above, on the runs recorded in `.claude/task-runner.log`.

The self-test caught two genuine defects before the runner was trusted:

1. Claude's own prose mentioning "rate limit" was classified as a usage limit.
   Fixed by scanning only error channels (`Get-SignalText`), never narration.
2. `.vscode/tasks.json` could not be un-ignored under an ignored `.vscode/`
   directory. Fixed by ignoring `.vscode/*` per file instead.

## Tests Failed

None outstanding. Failures seen during development and fixed: the two above,
plus PowerShell-specific defects (`$Branch`/`$branch` and `$resume`/`$Resume`
case-insensitive collisions with switch parameters, `$(` interpolation inside a
regex string, `SetThreadExecutionState` signed/unsigned conversion, git stderr
raising `NativeCommandError` under `$ErrorActionPreference = 'Stop'`, and
`Get-Command claude` resolving to `claude.ps1`, which CreateProcess cannot run).

## Known Issues

- The guide is split across three files rather than a single root
  `developer-guide.md`; `guide.files` in `.claude/config.yaml` is the mapping.
- The runner cannot verify Phase 1 completeness itself. It relies on Claude
  checking the requirements against the guide and writing `Phase 1 = COMPLETE`
  into the Phase Status section. A dishonest checkpoint would be trusted.

## Decisions Made

- Windows PowerShell for the runner: the repository root has no npm or Python
  toolchain, and the target platform is Windows.
- Checkpoint writes are section-targeted, so the runner and Claude can both
  write this file without erasing each other.
- The runner's git tool policy denies `reset`, `clean`, `checkout`, `restore`,
  `stash`, `push` and `rebase` for unattended runs. Nothing it does can discard
  work.

## Last Claude Action

Attempt 2 ended: Claude completed the turn.

## Last Checkpoint

2026-08-14 12:58:03 +05:30

## Interruption Reason

none

## Resume Instructions

Run `scripts\claude-task-runner.ps1`, or in VS Code: Terminal → Run Task →
"Claude Long Running Task".

The runner will start a fresh Claude session and hand it
`.claude/prompts/resume-prompt.md`, which directs Claude to read the guide files
above, then this checkpoint, then `git status`, `git diff`, `git diff --cached`
and recent history, and to continue from the first genuinely unfinished Phase 1
task. A new session never assumes it remembers the previous one.
