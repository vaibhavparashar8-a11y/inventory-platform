You are resuming an existing development task.

Do NOT restart the task from the beginning.

First read, in this order:

1. The guide files listed under "Source of truth" above — these are this
   project's developer guide.
2. `.claude/task-state.md`

Then inspect the actual repository:

3. `git status`
4. `git diff`
5. `git diff --cached`
6. `git log --oneline -10` and, where useful, `git show` on the recent commits

Determine the current Phase 1 implementation state from the actual repository.

The Git repository and the filesystem are the source of truth for what has
actually been implemented. `.claude/task-state.md` is a hint, not an authority.

Compare the current repository state against `.claude/task-state.md`. If the
checkpoint and the repository disagree, trust the repository and filesystem and
correct the checkpoint.

Do not duplicate completed work. Do not undo completed work. Continue from the
first genuinely unfinished Phase 1 task.

Follow all architecture, coding, testing, documentation and git instructions in
the guide files. In particular: work only on the branch named below, never on
`main`; commit in logical units with Conventional Commit messages; keep
`docs/DEVELOPER_GUIDE.md` updated in the same change as the code.

You must never run a destructive git command — no `git reset --hard`, no
`git clean -fd`, no `git checkout -- .`, no `git stash`. There may be
uncommitted work from a previous session that is real progress. Preserve it.

Checkpoint discipline for this run — this matters, because this session can be
interrupted at any moment by a usage limit:

- Update `.claude/task-state.md` after every meaningful milestone: a feature
  finished, an important component changed, a migration written, an API
  completed, a service completed, tests written or run, an important error hit,
  and always immediately before a commit and before any long-running command.
- Record only what has actually happened on disk and in git. Never write
  aspirational progress into the checkpoint.
- Keep the existing section headings in `.claude/task-state.md` intact; the
  runner reads and writes some of those sections itself.
- Before you stop for any reason, update `.claude/task-state.md`.

Do not declare Phase 1 complete on your own convenience. Phase 1 is complete
only when the guide's Phase 1 requirements and its Definition of Done are all
met: implementation done, required tests written and actually passing,
`./mvnw verify` green, docs updated. When and only when that is true, set the
"Phase Status" section of `.claude/task-state.md` to exactly:

    Phase 1 = COMPLETE

If it is not true, say precisely what remains.
