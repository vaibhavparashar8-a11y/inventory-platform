# State.ps1 — logging, git inspection, and the persistent checkpoint file.
#
# The checkpoint is shared between two writers: this runner, which owns the
# mechanical sections (git commit, branch, working tree, interruption reason,
# timestamps), and Claude, which owns the narrative sections (completed work,
# decisions, known issues). Writes are therefore section-targeted — the runner
# never rewrites the whole file, or it would erase Claude's progress notes.

Set-StrictMode -Version Latest

$script:LogPath = $null

function Initialize-RunnerLog {
    param([Parameter(Mandatory)][string]$Path, [int]$History = 10)

    $directory = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $directory)) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }
    if (Test-Path -LiteralPath $Path) {
        $size = (Get-Item -LiteralPath $Path).Length
        if ($size -gt 5MB) {
            $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
            Move-Item -LiteralPath $Path -Destination "$Path.$stamp" -Force
            Get-ChildItem -LiteralPath $directory -Filter "$(Split-Path -Leaf $Path).*" |
                Sort-Object LastWriteTime -Descending |
                Select-Object -Skip $History |
                Remove-Item -Force -ErrorAction SilentlyContinue
        }
    }
    $script:LogPath = $Path
}

function Write-RunnerLog {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$Message,
        [ValidateSet('info', 'warn', 'error', 'claude')][string]$Level = 'info'
    )

    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    if ($Level -eq 'claude') {
        $console = $Message
        $colour = 'Gray'
    }
    else {
        $console = "[Claude Runner] $Message"
        $colour = switch ($Level) {
            'warn' { 'Yellow' }
            'error' { 'Red' }
            default { 'Cyan' }
        }
    }
    Write-Host $console -ForegroundColor $colour

    if ($null -ne $script:LogPath) {
        Add-Content -LiteralPath $script:LogPath -Encoding UTF8 -Value "$stamp [$Level] $Message"
    }
}

# ---------------------------------------------------------------- git ----

function Invoke-Git {
    <#
      Windows PowerShell 5.1 turns a native command's stderr into ErrorRecords,
      which become terminating errors under $ErrorActionPreference = 'Stop' —
      even when git exited 0 and merely reported "Switched to branch". stderr is
      therefore merged deliberately and flattened to plain strings.
    #>
    param([Parameter(Mandatory)][string[]]$Arguments)

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & git @Arguments 2>&1 | ForEach-Object { "$_" }
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    return @{ ExitCode = $exitCode; Output = ($output -join "`n") }
}

function Get-GitSnapshot {
    <#
      Everything the checkpoint needs to describe the repository right now.
      Read-only: this function never mutates the working tree.
    #>
    $commit = (Invoke-Git -Arguments @('rev-parse', 'HEAD')).Output.Trim()
    $short = (Invoke-Git -Arguments @('rev-parse', '--short', 'HEAD')).Output.Trim()
    $branch = (Invoke-Git -Arguments @('rev-parse', '--abbrev-ref', 'HEAD')).Output.Trim()
    $porcelain = (Invoke-Git -Arguments @('status', '--porcelain')).Output
    $log = (Invoke-Git -Arguments @('log', '--oneline', '-10')).Output

    $dirty = ($porcelain.Trim().Length -gt 0)
    $changed = @()
    $untracked = @()
    foreach ($line in ($porcelain -split "`n")) {
        if ($line.Trim().Length -eq 0) { continue }
        if ($line -match '^\?\?\s+(.*)$') { $untracked += $Matches[1].Trim() }
        elseif ($line.Length -gt 3) { $changed += $line.Substring(3).Trim() }
    }

    return @{
        Commit      = $commit
        ShortCommit = $short
        Branch      = $branch
        Dirty       = $dirty
        Status      = if ($dirty) { 'modified' } else { 'clean' }
        Porcelain   = $porcelain.Trim()
        Changed     = $changed
        Untracked   = $untracked
        RecentLog   = $log.Trim()
    }
}

# --------------------------------------------------------- checkpoint ----

function Get-StateSection {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Heading)

    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    $content = Get-Content -LiteralPath $Path -Encoding UTF8 -Raw
    # Built by concatenation, not interpolation: '$(' inside a double-quoted
    # PowerShell string is a subexpression, not a regex anchor and group.
    $pattern = '(?ms)^##[ \t]+' + [regex]::Escape($Heading) + '[ \t]*\r?$(.*?)(?=^##[ \t]|\z)'
    if ($content -match $pattern) { return $Matches[1].Trim() }
    return $null
}

function Set-StateSection {
    <#
      Replaces the body of one '## Heading' section, leaving every other section
      byte-for-byte untouched. Appends the section if it is missing, so a
      checkpoint edited by Claude can never lose a runner-owned field.
    #>
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Heading,
        [Parameter(Mandatory)][AllowEmptyString()][string]$Body
    )

    $content = Get-Content -LiteralPath $Path -Encoding UTF8 -Raw
    $replacement = "## $Heading`r`n`r`n$($Body.TrimEnd())`r`n`r`n"
    $pattern = '(?ms)^##[ \t]+' + [regex]::Escape($Heading) + '[ \t]*\r?$.*?(?=^##[ \t]|\z)'

    if ($content -match $pattern) {
        # A MatchEvaluator, not a replacement string: the body may legitimately
        # contain '$' (git output, code snippets) which would otherwise be read
        # as a substitution token.
        $evaluator = [System.Text.RegularExpressions.MatchEvaluator] { param($m) $replacement }
        $updated = [regex]::Replace($content, $pattern, $evaluator)
    }
    else {
        $updated = $content.TrimEnd() + "`r`n`r`n" + $replacement
    }
    Set-Content -LiteralPath $Path -Value $updated -Encoding UTF8 -NoNewline
}

function Update-RunnerCheckpoint {
    <#
      Writes the mechanical half of the checkpoint from observed reality. Every
      value here comes from git or the clock — nothing is inferred, and nothing
      Claude wrote is touched.
    #>
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][hashtable]$Git,
        [string]$Interruption = 'none',
        [string]$LastAction = '',
        [string]$Branch = ''
    )

    $branchName = $Git.Branch
    if ($Branch) { $branchName = $Branch }

    Set-StateSection -Path $Path -Heading 'Current Git Commit' -Body $Git.Commit
    Set-StateSection -Path $Path -Heading 'Current Branch' -Body $branchName

    $tree = "$($Git.Status)"
    if ($Git.Dirty) {
        $tree += "`r`n`r`n``````text`r`n$($Git.Porcelain)`r`n``````"
    }
    Set-StateSection -Path $Path -Heading 'Working Tree Status' -Body $tree

    if ($LastAction) {
        Set-StateSection -Path $Path -Heading 'Last Claude Action' -Body $LastAction
    }
    Set-StateSection -Path $Path -Heading 'Interruption Reason' -Body $Interruption
    Set-StateSection -Path $Path -Heading 'Last Checkpoint' -Body (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')
}

function Test-PhaseComplete {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][int]$Phase)

    $section = Get-StateSection -Path $Path -Heading 'Phase Status'
    if ($null -eq $section) { return $false }
    return ($section -match "(?im)^\s*Phase\s+$Phase\s*=\s*COMPLETE\s*$")
}
