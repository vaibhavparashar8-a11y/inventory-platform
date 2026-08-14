# Claude task runner

A project-local runner for long, unattended Claude Code work. It starts a phase,
and keeps it going across usage-limit pauses without supervision:

```text
run -> usage limit -> checkpoint -> wait for the reported reset ->
start a fresh Claude process -> re-read the guide, the checkpoint and git ->
continue from the first genuinely unfinished task
```

It does not bypass anything. One account, one session at a time, the normal
Claude Code installation and your own sign-in. Pause, wait, retry, resume.

## Quick start

```powershell
scripts\claude-task.cmd -Probe          # check the launcher and sign-in (one tiny turn)
scripts\claude-task.cmd -SelfTest       # check limit/network/auth/build classification
scripts\claude-task.cmd -SimulateLimit  # full pause-and-resume rehearsal, no quota spent
scripts\claude-task.cmd                 # start the real run and walk away
```

In VS Code: **Terminal → Run Task → Claude Long Running Task** (also *status*,
*probe*, *self-test* and *simulate usage limit*).

There is no `npm run claude:task`: the repository root is a Maven build with no
`package.json`, and `web-ui/` does not exist until Phase 1. `claude-task.cmd` is
the equivalent entry point.

Stop the runner with **Ctrl-C**. Nothing is lost — the checkpoint is written
after every attempt, and the runner never discards work.

## Layout

```text
.claude/
├── config.yaml          runner configuration
├── task-state.md        the persistent checkpoint
├── task-runner.log      rolling log (gitignored)
└── prompts/
    ├── start-prompt.md  first attempt of a phase
    └── resume-prompt.md every attempt after an interruption
scripts/
├── claude-task-runner.ps1   orchestration and the retry loop
├── claude-task.cmd          entry point shim
└── claude-runner/
    ├── Config.ps1     minimal YAML reader
    ├── State.ps1      logging, git inspection, section-targeted checkpointing
    ├── Detect.ps1     why an attempt ended, and when to retry
    ├── Wait.ps1       reset waiting, backoff, keep-awake
    ├── Process.ps1    launching Claude and streaming its output
    ├── Prompt.ps1     prompt assembly
    └── Simulate.ps1   canned scenarios and the classifier self-test
.vscode/tasks.json     the VS Code tasks
```

## Source of truth

The runner assumes nothing about a previous Claude session, because sessions do
not survive a usage-limit pause. Every attempt is a cold start pointed at:

1. the guide files — `docs/DEVELOPER_GUIDE.md`, `BUILD_PROMPT.md`, `CLAUDE.md`
2. the git repository and its history
3. `.claude/task-state.md`
4. the current working tree, including uncommitted changes

This repository has no root `developer-guide.md`; `guide.files` in
`.claude/config.yaml` is the mapping, first entry primary. Add or reorder files
there rather than editing prompts.

Where the checkpoint and the repository disagree, **the repository wins** and the
checkpoint is corrected. That rule is in both prompt templates.

## Usage-limit detection

`Detect.ps1` classifies each attempt into exactly one of six outcomes:

| Outcome | What the runner does |
|---|---|
| `success` | Stop if the checkpoint says the phase is complete, otherwise continue |
| `usage_limit` | Checkpoint, wait for the reset, start a fresh session |
| `network_error` | Exponential backoff with jitter, bounded attempts |
| `auth_error` | Stop immediately and tell you — never retried |
| `max_turns` | Healthy but unfinished; start a fresh session at once |
| `crash` | Checkpoint, inspect git, retry a bounded number of times |

Two details matter more than the pattern list:

- **Only error channels are scanned.** The signal is the `result` event and
  anything printed outside the JSON stream (stderr). Claude's own prose is
  excluded on purpose — on this codebase it discusses reservation limits, rate
  limiting and retry policy constantly, and matching on that would park the
  runner for hours over a sentence in a commit message. The self-test asserts
  this.
- **A failing build is not a usage limit.** A red test leaves Claude working, not
  waiting.

## Reset waiting

No five-hour period is hard-coded. `Get-ResetTime` reads whatever Claude
actually said, in this order:

1. the machine-readable form, `Claude AI usage limit reached|<unix-epoch>`
2. relative — "try again in 2 hours 14 minutes", "resets in 45 minutes"
3. absolute — "resets at 3pm", "try again at 14:30" (rolls to tomorrow if past)
4. a `retry-after` value echoed into the error text

The parsed time plus `retry.reset_buffer_minutes` is the wake-up moment. When
nothing parseable is found, the runner waits `retry.fallback_retry_minutes` and
tries again — a short retry, never a guessed multi-hour sleep.

While waiting it logs the remaining time each minute, and holds the machine awake
for the duration (process-scoped; your power plan is not modified).

## Git as the recovery mechanism

Before every attempt the runner records HEAD, the branch and the porcelain status
into the checkpoint, and the resume prompt makes Claude inspect `git status`,
`git diff`, `git diff --cached` and recent history before touching anything.

The runner **cannot** destroy work:

- It only ever checks out a *branch*, never paths.
- `git reset`, `clean`, `checkout`, `restore`, `stash`, `push` and `rebase` are
  in the Claude `disallowedTools` list for unattended runs.
- A failed branch switch stops the runner with an explanation instead of
  stashing or forcing.
- Uncommitted changes are treated as real progress from an interrupted session
  and are recorded, never cleared.

If you *want* a destructive command run, run it yourself.

## Checkpointing

`.claude/task-state.md` has two writers. The runner owns the mechanical sections
(current commit, branch, working tree, interruption reason, last action, last
checkpoint); Claude owns the narrative ones (completed, current and remaining
work, tests, known issues, decisions).

Writes are **section-targeted** — the runner replaces one `## Heading` body at a
time and leaves every other byte alone, so the two writers cannot erase each
other. Keep the headings intact when editing by hand.

The runner checkpoints on start and after every attempt. Claude is instructed to
checkpoint at each milestone: a feature finished, a component changed, a
migration written, an API or service completed, tests run, an important error,
before every commit, and before any long-running command. Checkpoint plus commit
plus working tree together describe the state, so an interruption with
uncommitted changes still resumes correctly.

## Phase completion

The runner never infers completeness from Claude exiting. Claude must verify the
phase requirements and the Definition of Done against the guide — implementation,
tests actually passing, `./mvnw verify` green, docs updated — and only then write

```text
Phase 1 = COMPLETE
```

into the `## Phase Status` section. The runner reads that literally and stops. A
clean exit without it just starts the next attempt.

## Configuration

`.claude/config.yaml`. The parser handles comments, two levels of nesting,
scalars and flow lists — keep to that shape.

| Key | Meaning |
|---|---|
| `project.phase` / `project.branch` | Which phase, and the branch to work on. `main` is refused |
| `project.baseline_commit` | Filled in from HEAD on the first run; keeps the baseline recoverable |
| `guide.files` / `guide.phase_brief` | Source-of-truth documents; a missing file warns and is skipped, an empty brief is ignored |
| `retry.reset_buffer_minutes` | Added to a reported reset time (default 2) |
| `retry.fallback_retry_minutes` | Used when no reset time can be parsed (default 10) |
| `retry.max_retries` | `unlimited`, or a cap on usage-limit pauses |
| `retry.network_*` / `retry.crash_max_attempts` | Backoff bounds and crash ceiling |
| `claude.allowed_tools` / `disallowed_tools` | The unattended tool policy |
| `claude.max_turns` | Turns per attempt; the runner resumes across attempts |
| `claude.attempt_timeout_minutes` | Wall-clock ceiling per attempt, `0` to disable |
| `runner.keep_awake` | Hold the machine awake during a run |

## Exit codes

`0` finished or stopped cleanly · `2` precondition failed · `3` branch switch
refused · `4` retry ceiling reached · `5` network failures did not clear ·
`6` authentication — sign in again · `7` repeated crashes · `8` probe failed.

## Testing it

`-SelfTest` checks the classifier against canned transcripts, including the two
cases that matter: a usage limit must be detected with its reset time, and a
failing build mentioning "limit" must not be.

`-SimulateLimit` runs the whole loop with the child process replaced by a canned
transcript: attempt 1 hits a simulated limit with a reset ~1 minute out, the
checkpoint is written, the runner waits for real, then a fresh attempt resumes
and continues. It spends no quota and needs no real limit.

`-DryRun` prints the assembled prompt and the exact Claude command line without
starting anything. `-Probe` starts Claude once with a trivial prompt to confirm
the launcher, stdin plumbing and sign-in.

`-Branch <name>` overrides the configured branch — useful for exercising the
runner without moving off the branch you are on.
