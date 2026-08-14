You are starting Phase 1 of this project as a long-running, unattended task.

Phase 0 is already complete, committed and pushed. Treat the current git history
as an immutable baseline. Do not modify, revert, squash or rewrite Phase 0.

First read, in this order:

1. The guide files listed under "Source of truth" above — these are this
   project's developer guide, including the phased build plan and the
   Definition of Done that applies to every phase.
2. `.claude/task-state.md`, which holds the Phase 1 baseline and the running
   checkpoint.

Then inspect the actual repository with `git status`, `git diff`,
`git diff --cached` and `git log --oneline -10`, so that you are working from
what is really on disk rather than from any assumption.

Then implement Phase 1 as the guide defines it — not as you would define it.
Derive the task list from the guide's Phase 1 section, record that list in the
"Remaining Work" section of `.claude/task-state.md`, and work through it.

Rules for this unattended run:

- Work only on the branch named below. Never commit or push to `main`.
- Commit in logical units with Conventional Commit messages, following the git
  workflow in the guide. Do not commit every tiny edit.
- Never run a destructive git command — no `git reset --hard`, no
  `git clean -fd`, no `git checkout -- .`, no `git stash`.
- Build and test as you go. `./mvnw verify` must be green before each commit.
- Never invent a business rule, especially around money or stock quantities. If
  something is genuinely ambiguous, record the question in the guide's open
  questions register and in the "Known Issues" section of the checkpoint, then
  continue with everything that is not blocked by it.
- Log every assumption and shortcut in the known-issues register.

Checkpoint discipline for this run — this matters, because this session can be
interrupted at any moment by a usage limit:

- Update `.claude/task-state.md` after every meaningful milestone: a feature
  finished, an important component changed, a migration written, an API
  completed, a service completed, tests written or run, an important error hit,
  and always immediately before a commit and before any long-running command.
- Record only what has actually happened on disk and in git. Never write
  aspirational progress into the checkpoint.
- Keep the existing section headings intact; the runner reads and writes some
  of those sections itself.
- Before you stop for any reason, update `.claude/task-state.md`.

Phase 1 is complete only when the guide's Phase 1 requirements and Definition of
Done are all met: implementation done, required tests written and actually
passing, `./mvnw verify` green, docs updated. When and only when that is true,
set the "Phase Status" section of `.claude/task-state.md` to exactly:

    Phase 1 = COMPLETE

If it is not true, say precisely what remains.
