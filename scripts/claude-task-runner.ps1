<#
.SYNOPSIS
    Long-running Claude Code task runner for this repository.

.DESCRIPTION
    Starts (or resumes) a phase of work in Claude Code, and keeps it going
    across usage-limit pauses without supervision:

        run -> usage limit -> checkpoint -> wait for the reported reset ->
        start a fresh Claude process -> re-read the guide, the checkpoint and
        git -> continue from the first genuinely unfinished task.

    It does not bypass anything. It pauses, waits, retries and resumes using the
    normal Claude Code installation and the user's own authentication.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\claude-task-runner.ps1

.EXAMPLE
    scripts\claude-task-runner.ps1 -SelfTest
    scripts\claude-task-runner.ps1 -SimulateLimit
    scripts\claude-task-runner.ps1 -Status
#>

[CmdletBinding()]
param(
    # Force the resume prompt even on the first attempt.
    [switch]$Resume,
    # Verify detection, checkpointing and the wait/resume loop without calling
    # Claude and without spending quota.
    [switch]$SimulateLimit,
    # Run only the classifier checks and exit.
    [switch]$SelfTest,
    # Print the current baseline, checkpoint and git state, then exit.
    [switch]$Status,
    # Show what would be run without starting Claude.
    [switch]$DryRun,
    # Start Claude once with a trivial prompt to prove the launcher, stdin
    # plumbing and authentication work. Costs one very small turn.
    [switch]$Probe,
    # Override the phase from config.yaml.
    [int]$Phase = 0,
    # Override the working branch from config.yaml. Mainly for testing the
    # runner itself without moving off the branch you are on.
    [string]$Branch,
    [string]$ConfigPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$ModuleDir = Join-Path $PSScriptRoot 'claude-runner'
foreach ($module in @('Config.ps1', 'State.ps1', 'Detect.ps1', 'Wait.ps1', 'Process.ps1', 'Simulate.ps1')) {
    . (Join-Path $ModuleDir $module)
}
. (Join-Path $ModuleDir 'Prompt.ps1')

if (-not $ConfigPath) { $ConfigPath = Join-Path $RepoRoot '.claude\config.yaml' }
$config = Read-RunnerConfig -Path $ConfigPath

$projectName = Get-RunnerSetting -Config $config -Section 'project' -Key 'name' -Default 'project'
if ($Phase -le 0) { $Phase = [int](Get-RunnerSetting -Config $config -Section 'project' -Key 'phase' -Default 1) }
$workBranch = Get-RunnerSetting -Config $config -Section 'project' -Key 'branch' -Default "feature/$Phase-work"
if ($Branch) { $workBranch = $Branch }
if ($workBranch -eq 'main' -or $workBranch -eq 'master') {
    Write-Host "[Claude Runner] Refusing to run on '$workBranch'. The guide forbids committing to main." -ForegroundColor Red
    exit 2
}
$statePath = Join-Path $RepoRoot (Get-RunnerSetting -Config $config -Section 'state' -Key 'file' -Default '.claude\task-state.md')
$logPath = Join-Path $RepoRoot (Get-RunnerSetting -Config $config -Section 'state' -Key 'log' -Default '.claude\task-runner.log')

Initialize-RunnerLog -Path $logPath -History ([int](Get-RunnerSetting -Config $config -Section 'state' -Key 'log_history' -Default 10))
Set-Location $RepoRoot

# ------------------------------------------------------------- helpers ----

function Stop-Runner {
    param([string]$Message, [int]$Code = 0, [string]$Level = 'info')
    if ($Message) { Write-RunnerLog -Message $Message -Level $Level }
    Disable-KeepAwake
    exit $Code
}

function Assert-Preconditions {
    if ((Invoke-Git -Arguments @('rev-parse', '--is-inside-work-tree')).ExitCode -ne 0) {
        Stop-Runner -Message "Not a git repository: $RepoRoot" -Code 2 -Level 'error'
    }
    if (-not (Test-Path -LiteralPath $statePath)) {
        Stop-Runner -Message "Checkpoint file missing: $statePath" -Code 2 -Level 'error'
    }
    foreach ($guide in (Get-GuideFiles -Config $config -RepoRoot $RepoRoot)) {
        Write-RunnerLog -Message "Reading $guide ..."
    }
    if (-not $SimulateLimit -and -not $DryRun -and -not $SelfTest) {
        if ($null -eq (Get-Command claude -ErrorAction SilentlyContinue)) {
            Stop-Runner -Message "'claude' is not on PATH. Install: npm i -g @anthropic-ai/claude-code" -Code 2 -Level 'error'
        }
    }
}

function Confirm-WorkingBranch {
    <#
      Puts the runner on the phase branch, creating it from the current commit
      the first time. Only ever a checkout of a branch — never a checkout of
      paths, which would discard working-tree changes.
    #>
    $current = (Invoke-Git -Arguments @('rev-parse', '--abbrev-ref', 'HEAD')).Output.Trim()
    if ($current -eq $workBranch) { return }

    if ($DryRun) {
        Write-RunnerLog -Message "DRY RUN: would switch from '$current' to '$workBranch'."
        return
    }

    $exists = (Invoke-Git -Arguments @('rev-parse', '--verify', '--quiet', "refs/heads/$workBranch")).ExitCode -eq 0
    if ($exists) {
        Write-RunnerLog -Message "Switching to existing branch '$workBranch'."
        $result = Invoke-Git -Arguments @('checkout', $workBranch)
    }
    else {
        Write-RunnerLog -Message "Creating branch '$workBranch' from $current."
        $result = Invoke-Git -Arguments @('checkout', '-b', $workBranch)
    }

    if ($result.ExitCode -ne 0) {
        # Uncommitted work that would be overwritten is real progress, not
        # something to clear out of the way.
        Stop-Runner -Code 3 -Level 'error' -Message @"
Could not switch to '$workBranch'. Git said:
$($result.Output)
The runner will not discard or stash uncommitted work. Resolve this by hand.
"@
    }
}

function Write-Baseline {
    param([Parameter(Mandatory)][hashtable]$Git)

    $recorded = Get-RunnerSetting -Config $config -Section 'project' -Key 'baseline_commit' -Default ''
    if (-not $recorded) {
        Set-RunnerSetting -Path $ConfigPath -Section 'project' -Key 'baseline_commit' -Value $Git.Commit
        $recorded = $Git.Commit
        # Keep the in-memory config in step, so this run's prompt carries the
        # baseline rather than reporting it as not yet recorded.
        $config['project']['baseline_commit'] = $recorded
        Write-RunnerLog -Message "Recorded Phase $Phase baseline commit $($Git.ShortCommit) in config.yaml."
    }

    $body = @"
Git commit:
$recorded

Git branch:
$workBranch

Working tree at baseline:
$($Git.Status)

Phase $($Phase - 1):
completed and pushed

Phase ${Phase}:
in progress
"@
    if ($null -eq (Get-StateSection -Path $statePath -Heading "Phase $Phase Baseline")) {
        Set-StateSection -Path $statePath -Heading "Phase $Phase Baseline" -Body $body
    }
    return $recorded
}

function Show-Status {
    $snapshot = Get-GitSnapshot
    Write-RunnerLog -Message "Project:  $projectName (phase $Phase)"
    Write-RunnerLog -Message "Branch:   $($snapshot.Branch)  (runner branch: $workBranch)"
    Write-RunnerLog -Message "Commit:   $($snapshot.ShortCommit)"
    Write-RunnerLog -Message "Tree:     $($snapshot.Status)"
    Write-RunnerLog -Message "Baseline: $(Get-RunnerSetting -Config $config -Section 'project' -Key 'baseline_commit' -Default '(not yet recorded)')"
    $complete = Test-PhaseComplete -Path $statePath -Phase $Phase
    Write-RunnerLog -Message "Phase $Phase complete: $complete"
    $last = Get-StateSection -Path $statePath -Heading 'Last Checkpoint'
    Write-RunnerLog -Message "Last checkpoint: $last"
    $reason = Get-StateSection -Path $statePath -Heading 'Interruption Reason'
    Write-RunnerLog -Message "Interruption reason: $reason"
    if ($snapshot.Dirty) {
        Write-RunnerLog -Message "Uncommitted changes present (preserved, never discarded):"
        foreach ($file in ($snapshot.Changed + $snapshot.Untracked)) { Write-RunnerLog -Message "    $file" }
    }
}

# ---------------------------------------------------------------- main ----

Write-RunnerLog -Message "Starting Phase $Phase for $projectName ..."

if ($SelfTest) {
    Write-RunnerLog -Message "Self-test: usage-limit, network, auth and build-failure classification."
    $ok = Invoke-ClassifierSelfTest
    if ($ok) { Stop-Runner -Message "Self-test passed." -Code 0 }
    Stop-Runner -Message "Self-test FAILED." -Code 1 -Level 'error'
}

Assert-Preconditions

if ($Status) {
    Show-Status
    Stop-Runner -Code 0
}

if ($Probe) {
    Write-RunnerLog -Message "Probe: starting Claude once with a trivial prompt to check the launcher and sign-in."
    $probeRun = Invoke-ClaudeAttempt -Prompt 'Reply with exactly: RUNNER OK' `
        -Arguments @('-p', '--output-format', 'stream-json', '--verbose', '--max-turns', '1') `
        -WorkingDirectory $RepoRoot -TimeoutMinutes 5
    $probeClass = Get-AttemptClassification -ExitCode $probeRun.ExitCode -Transcript $probeRun.Transcript `
        -ResultSubtype $probeRun.Subtype -ResultIsError $probeRun.IsError
    Write-RunnerLog -Message "Probe result: $($probeClass.Category) - $($probeClass.Reason)"
    if ($probeClass.Category -eq 'success') { Stop-Runner -Message "Probe passed; the runner can start Claude." -Code 0 }
    Stop-Runner -Message "Probe did not succeed. Fix this before an unattended run." -Code 8 -Level 'error'
}

if ([bool](Get-RunnerSetting -Config $config -Section 'runner' -Key 'keep_awake' -Default $true)) {
    Enable-KeepAwake
    Write-RunnerLog -Message "Keeping this machine awake for the duration of the run."
}

Confirm-WorkingBranch
$snapshot = Get-GitSnapshot
$baseline = Write-Baseline -Git $snapshot
Write-RunnerLog -Message "Git baseline: $($baseline.Substring(0, 7))  |  HEAD: $($snapshot.ShortCommit)  |  tree: $($snapshot.Status)"

if ($snapshot.Dirty) {
    Write-RunnerLog -Message "Working tree has uncommitted changes; recording them in the checkpoint and leaving them alone." -Level 'warn'
}

Update-RunnerCheckpoint -Path $statePath -Git $snapshot -Interruption 'none' -Branch $workBranch `
    -LastAction "Runner started at $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')."
Write-RunnerLog -Message "Checkpoint saved."

$bufferMinutes = [int](Get-RunnerSetting -Config $config -Section 'retry' -Key 'reset_buffer_minutes' -Default 2)
$fallbackMinutes = [int](Get-RunnerSetting -Config $config -Section 'retry' -Key 'fallback_retry_minutes' -Default 10)
$maxRetriesRaw = Get-RunnerSetting -Config $config -Section 'retry' -Key 'max_retries' -Default 'unlimited'
$networkStart = [int](Get-RunnerSetting -Config $config -Section 'retry' -Key 'network_backoff_start_seconds' -Default 30)
$networkMax = [int](Get-RunnerSetting -Config $config -Section 'retry' -Key 'network_backoff_max_seconds' -Default 900)
$networkAttempts = [int](Get-RunnerSetting -Config $config -Section 'retry' -Key 'network_max_attempts' -Default 6)
$crashAttempts = [int](Get-RunnerSetting -Config $config -Section 'retry' -Key 'crash_max_attempts' -Default 3)
$timeoutMinutes = [int](Get-RunnerSetting -Config $config -Section 'claude' -Key 'attempt_timeout_minutes' -Default 0)

$unlimited = ("$maxRetriesRaw" -eq 'unlimited')
$maxRetries = 0
if (-not $unlimited) { $maxRetries = [int]$maxRetriesRaw }

$claudeArgs = Get-ClaudeArguments -Config $config
$attempt = 0
$pauses = 0
$networkFailures = 0
$crashes = 0
$isResume = [bool]$Resume

while ($true) {
    $attempt++

    if (Test-PhaseComplete -Path $statePath -Phase $Phase) {
        Stop-Runner -Message "Phase $Phase = COMPLETE, verified from the checkpoint. Nothing to do." -Code 0
    }

    $mode = 'start'
    if ($isResume) { $mode = 'resume' }
    $prompt = New-RunnerPrompt -Config $config -RepoRoot $RepoRoot -Phase $Phase -Branch $workBranch -Mode $mode

    if ($DryRun) {
        Write-RunnerLog -Message "DRY RUN: claude $($claudeArgs -join ' ')"
        Write-RunnerLog -Message "DRY RUN: prompt is $($prompt.Length) characters, mode '$mode'."
        Write-Host $prompt
        Stop-Runner -Code 0
    }

    if ($isResume) { Write-RunnerLog -Message "Resuming ..." }
    Write-RunnerLog -Message "Verifying Git state ..."
    $snapshot = Get-GitSnapshot
    Write-RunnerLog -Message "  HEAD $($snapshot.ShortCommit) on $($snapshot.Branch), tree $($snapshot.Status)"
    Write-RunnerLog -Message "Claude is working (attempt $attempt, mode '$mode') ..."

    if ($SimulateLimit) {
        # First attempt hits a simulated limit; the resumed attempt succeeds, so
        # the whole pause-checkpoint-wait-resume path is exercised end to end.
        $scenario = 'success'
        if ($attempt -eq 1) { $scenario = 'usage_limit_epoch' }
        $run = Invoke-SimulatedAttempt -Scenario $scenario -ResetInMinutes 1
    }
    else {
        $run = Invoke-ClaudeAttempt -Prompt $prompt -Arguments $claudeArgs -WorkingDirectory $RepoRoot -TimeoutMinutes $timeoutMinutes
    }

    $classification = Get-AttemptClassification -ExitCode $run.ExitCode -Transcript $run.Transcript `
        -ResultSubtype $run.Subtype -ResultIsError $run.IsError

    $snapshot = Get-GitSnapshot
    Update-RunnerCheckpoint -Path $statePath -Git $snapshot -Branch $workBranch `
        -Interruption $(if ($classification.Category -eq 'success') { 'none' } else { $classification.Category }) `
        -LastAction "Attempt $attempt ended: $($classification.Reason)."
    Write-RunnerLog -Message "Checkpoint saved."

    $isResume = $true

    switch ($classification.Category) {

        'success' {
            if (Test-PhaseComplete -Path $statePath -Phase $Phase) {
                Stop-Runner -Code 0 -Message "Claude verified Phase $Phase against the guide and marked it COMPLETE."
            }
            Write-RunnerLog -Message "Attempt finished cleanly, but Phase $Phase is not marked complete. Continuing." -Level 'warn'
            $networkFailures = 0
            $crashes = 0
            if ($SimulateLimit) {
                Stop-Runner -Code 0 -Message "SIMULATION complete: limit detected, state saved, waited, resumed, work continued."
            }
            continue
        }

        'max_turns' {
            Write-RunnerLog -Message "Turn ceiling reached with work outstanding; starting a fresh session to continue." -Level 'warn'
            $crashes = 0
            continue
        }

        'usage_limit' {
            $pauses++
            Write-RunnerLog -Message "Usage limit detected (matched: $($classification.Pattern))." -Level 'warn'
            Write-RunnerLog -Message "State saved."

            if (-not $unlimited -and $pauses -gt $maxRetries) {
                Stop-Runner -Code 4 -Level 'error' -Message "Reached the configured retry ceiling of $maxRetries usage-limit pauses. Stopping."
            }

            # Not $resume: PowerShell variable names are case-insensitive, and
            # that would overwrite the -Resume switch parameter.
            $resumeAt = Resolve-ResumeTime -Classification $classification -BufferMinutes $bufferMinutes -FallbackMinutes $fallbackMinutes
            Write-RunnerLog -Message "Reset time $($resumeAt.Source); waiting with a $bufferMinutes minute buffer."
            Wait-UntilTime -Target $resumeAt.Time -Purpose 'usage reset'
            Write-RunnerLog -Message "Usage window should be open again."
            $networkFailures = 0
            $crashes = 0
            continue
        }

        'network_error' {
            $networkFailures++
            if ($networkFailures -gt $networkAttempts) {
                Stop-Runner -Code 5 -Level 'error' -Message "Network failures did not clear after $networkAttempts attempts. Stopping so this is not mistaken for progress."
            }
            $delay = Get-BackoffDelay -Attempt $networkFailures -StartSeconds $networkStart -MaxSeconds $networkMax
            Write-RunnerLog -Message "Network error ($($classification.Pattern)). Backing off $delay seconds (attempt $networkFailures of $networkAttempts)." -Level 'warn'
            Start-Sleep -Seconds $delay
            continue
        }

        'auth_error' {
            Stop-Runner -Code 6 -Level 'error' -Message @"
Authentication problem: $($classification.Reason).
The runner will not retry this. Sign in again with 'claude' (then /login) and start the runner once more.
Nothing has been discarded; the checkpoint and your working tree are intact.
"@
        }

        default {
            $crashes++
            Write-RunnerLog -Message "$($classification.Reason). Inspecting the repository before deciding." -Level 'error'
            $tail = ($run.Transcript -split "`n" | Select-Object -Last 5) -join ' | '
            Write-RunnerLog -Message "  last output: $tail" -Level 'error'
            Write-RunnerLog -Message "  git says: HEAD $($snapshot.ShortCommit), tree $($snapshot.Status) - changes are preserved."

            if ($crashes -ge $crashAttempts) {
                Stop-Runner -Code 7 -Level 'error' -Message "Claude crashed $crashes times in a row. Stopping. The checkpoint records where it got to; nothing was reverted."
            }
            $delay = Get-BackoffDelay -Attempt $crashes -StartSeconds $networkStart -MaxSeconds $networkMax
            Write-RunnerLog -Message "Retrying in $delay seconds." -Level 'warn'
            Start-Sleep -Seconds $delay
            continue
        }
    }
}
