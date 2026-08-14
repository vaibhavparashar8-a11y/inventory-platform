# Prompt.ps1 — assemble the prompt handed to each Claude process.
#
# Every attempt is a brand new Claude session with no memory of the last one.
# The prompt therefore never says "carry on from before"; it names the source of
# truth — the guide, the checkpoint, and git — and tells Claude to derive the
# state from those.

Set-StrictMode -Version Latest

function Get-GuideFiles {
    <#
      Resolves the configured guide files against the repository, dropping any
      that do not exist so a renamed document degrades to a warning rather than
      a prompt that references a missing file.
    #>
    param([Parameter(Mandatory)][hashtable]$Config, [Parameter(Mandatory)][string]$RepoRoot)

    $configured = Get-RunnerSetting -Config $Config -Section 'guide' -Key 'files' -Default @()
    if ($configured -is [string]) { $configured = @($configured) }

    $resolved = @()
    foreach ($file in $configured) {
        $full = Join-Path $RepoRoot $file
        if (Test-Path -LiteralPath $full) { $resolved += $file }
        else { Write-RunnerLog -Message "Configured guide file not found, skipping: $file" -Level 'warn' }
    }

    $brief = Get-RunnerSetting -Config $Config -Section 'guide' -Key 'phase_brief' -Default ''
    if ($brief) {
        $briefPath = Join-Path $RepoRoot $brief
        if ((Test-Path -LiteralPath $briefPath) -and (Get-Item -LiteralPath $briefPath).Length -gt 0) {
            $resolved += $brief
        }
    }

    if ($resolved.Count -eq 0) {
        throw "No guide files exist. Check guide.files in .claude/config.yaml."
    }
    return $resolved
}

function New-RunnerPrompt {
    <#
      Mode 'start' on the very first attempt, 'resume' on every attempt after an
      interruption. The two differ only in framing: resume forbids restarting
      from scratch, start forbids touching the previous phase.
    #>
    param(
        [Parameter(Mandatory)][hashtable]$Config,
        [Parameter(Mandatory)][string]$RepoRoot,
        [Parameter(Mandatory)][int]$Phase,
        [Parameter(Mandatory)][string]$Branch,
        [ValidateSet('start', 'resume')][string]$Mode = 'resume'
    )

    $file = 'resume-prompt.md'
    if ($Mode -eq 'start') { $file = 'start-prompt.md' }
    $promptPath = Join-Path $RepoRoot ".claude\prompts\$file"
    if (-not (Test-Path -LiteralPath $promptPath)) {
        throw "Prompt template missing: $promptPath"
    }
    $body = Get-Content -LiteralPath $promptPath -Encoding UTF8 -Raw

    $guides = Get-GuideFiles -Config $Config -RepoRoot $RepoRoot
    $guideList = ($guides | ForEach-Object { "- $_" }) -join "`r`n"
    $statePath = Get-RunnerSetting -Config $Config -Section 'state' -Key 'file' -Default '.claude/task-state.md'
    $baseline = Get-RunnerSetting -Config $Config -Section 'project' -Key 'baseline_commit' -Default '(recorded in the checkpoint)'

    $header = @"
# Phase $Phase task

Source of truth, in priority order:

$guideList
- $statePath  (the persistent checkpoint)
- the git repository and the current working tree

Working branch: $Branch
Phase $Phase baseline commit: $baseline

This repository has no root ``developer-guide.md``; the guide is the file list
above, first entry primary. Where they disagree, the deeper reference wins over
the summary, and an explicit instruction in this prompt wins over both.

"@

    $footer = @"

---

Operational notes for this run:

- You are a fresh process. You do not remember any previous session. Everything
  you need is in the files listed above and in git.
- This session may be interrupted at any moment when the usage window closes. A
  runner will start a new session after the window reopens and hand it the same
  instructions. Work in a way that survives that: commit logical units, and keep
  $statePath honest and current.
- Never run a destructive git command. Uncommitted changes in the working tree
  may be real progress from an interrupted session.
- Work only on branch $Branch.
"@

    return ($header.TrimEnd() + "`r`n`r`n" + $body.Trim() + "`r`n" + $footer)
}
