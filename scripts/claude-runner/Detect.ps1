# Detect.ps1 — classify why a Claude Code attempt ended, and when to retry.
#
# The single most important rule here: not every failure is a usage limit, and a
# failing build is not a failure of the runner at all. Misclassifying a code or
# test failure as a usage limit would park the runner for hours over a red test.

Set-StrictMode -Version Latest

# Order matters: the first matching category wins.
$script:UsageLimitPatterns = @(
    'Claude AI usage limit reached',
    'usage limit reached',
    'you(?:''| ha)?ve (?:hit|reached) your .{0,40}limit',
    '\b(?:5|five)[- ]hour limit\b',
    'weekly limit reached',
    'plan limit',
    'quota (?:exceeded|exhausted)',
    'out of (?:usage|credits)',
    'limit (?:will )?reset(?:s)? (?:at|in)',
    'upgrade to (?:increase|raise) your (?:usage )?limit',
    'rate[_ ]limit[_ ]error',
    'rate limit(?:ed)?',
    'too many requests',
    'over capacity',
    'capacity constraints'
)

$script:AuthPatterns = @(
    'authentication[_ ]error',
    'invalid[_ ]api[_ ]key',
    'invalid bearer token',
    'oauth token (?:has )?expired',
    'please run [`'']?/?(?:claude )?login',
    'not logged in',
    'unauthori[sz]ed',
    '\b401\b'
)

$script:NetworkPatterns = @(
    'ECONNRESET', 'ECONNREFUSED', 'ETIMEDOUT', 'ENOTFOUND', 'EAI_AGAIN',
    'socket hang up', 'fetch failed', 'network error', 'connection error',
    'getaddrinfo', 'tls handshake', 'proxy error',
    '\b(?:502|503|504)\b', 'bad gateway', 'service unavailable', 'gateway timeout',
    'overloaded[_ ]error'
)

function Get-SignalText {
    <#
      Narrows a transcript to the parts that can legitimately report a failure:
      the result event, error fields, and anything printed outside the JSON
      stream (i.e. stderr).

      Claude's own prose is deliberately excluded. It routinely discusses rate
      limits, quotas and capacity while working on this codebase — reservation
      limits, throttling, retry policy — and matching on that would park the
      runner for hours over a sentence in a commit message.
    #>
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Transcript)

    $signal = New-Object System.Text.StringBuilder

    foreach ($line in ($Transcript -split "`r?`n")) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0) { continue }

        if (-not $trimmed.StartsWith('{')) {
            # Not part of the JSON stream: stderr, or a plain-text fallback.
            [void]$signal.AppendLine($trimmed)
            continue
        }

        $event = $null
        try { $event = $trimmed | ConvertFrom-Json } catch { [void]$signal.AppendLine($trimmed); continue }
        if ($null -eq $event -or -not ($event.PSObject.Properties.Name -contains 'type')) { continue }
        if ($event.type -ne 'result') { continue }

        foreach ($field in @('result', 'error', 'subtype')) {
            if ($event.PSObject.Properties.Name -contains $field -and $event.$field) {
                [void]$signal.AppendLine("$($event.$field)")
            }
        }
    }

    return $signal.ToString()
}

function Test-AnyPattern {
    param([string]$Text, [string[]]$Patterns)
    foreach ($pattern in $Patterns) {
        if ($Text -match $pattern) { return $pattern }
    }
    return $null
}

function Get-ResetTime {
    <#
      Extracts the moment the usage window reopens, from whatever Claude said.
      Returns $null when nothing parseable was found — the caller then falls
      back to retry.fallback_retry_minutes. No five-hour period is ever assumed.
    #>
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Text)

    # 1. The machine-readable form Claude Code emits in print mode:
    #    "Claude AI usage limit reached|1739500000"  (unix epoch seconds)
    if ($Text -match 'usage limit reached\s*\|\s*(\d{9,13})') {
        $raw = [int64]$Matches[1]
        if ($raw -gt 100000000000) { $raw = [int64][math]::Floor($raw / 1000) }
        return ([System.DateTimeOffset]::FromUnixTimeSeconds($raw)).LocalDateTime
    }

    # 2. Relative: "try again in 2 hours 14 minutes", "resets in 45 minutes".
    if ($Text -match '(?:try again|reset(?:s)?|available again|retry)\s+in\s+(?<rel>[^.\r\n]{1,40})') {
        $relative = $Matches['rel']
        $minutes = 0
        $matched = $false
        if ($relative -match '(\d+)\s*(?:hours?|hrs?|h)\b') { $minutes += [int]$Matches[1] * 60; $matched = $true }
        if ($relative -match '(\d+)\s*(?:minutes?|mins?|m)\b') { $minutes += [int]$Matches[1]; $matched = $true }
        if ($relative -match '(\d+)\s*(?:seconds?|secs?|s)\b' -and -not $matched) {
            return (Get-Date).AddSeconds([int]$Matches[1])
        }
        if ($matched) { return (Get-Date).AddMinutes($minutes) }
    }

    # 3. Absolute clock time: "resets at 3pm", "try again at 14:30".
    if ($Text -match '(?:reset(?:s)?|try again|available again)\s+at\s+(?<clock>\d{1,2}(?::\d{2})?\s*(?:am|pm)?)') {
        $clock = $Matches['clock'].Trim()
        $parsed = [datetime]::MinValue
        $formats = @('h:mmtt', 'htt', 'H:mm', 'HH:mm', 'h:mm tt', 'h tt')
        foreach ($format in $formats) {
            if ([datetime]::TryParseExact($clock.Replace(' ', ''), $format.Replace(' ', ''),
                    [Globalization.CultureInfo]::InvariantCulture,
                    [Globalization.DateTimeStyles]::None, [ref]$parsed)) {
                $now = Get-Date
                $target = $now.Date.AddHours($parsed.Hour).AddMinutes($parsed.Minute)
                if ($target -le $now) { $target = $target.AddDays(1) }
                return $target
            }
        }
    }

    # 4. Retry-After style header echoed into the error text.
    if ($Text -match 'retry[- ]after[":\s]+(\d+)') {
        return (Get-Date).AddSeconds([int]$Matches[1])
    }

    return $null
}

function Get-AttemptClassification {
    <#
      Returns a hashtable: Category, Reason, ResetAt (nullable), Pattern.
      Category is one of:
        success        — Claude finished the turn cleanly
        usage_limit    — pause until the window reopens
        auth_error     — stop and notify the user
        network_error  — retry with exponential backoff
        max_turns      — resume immediately, work is unfinished but healthy
        crash          — save state, retry a bounded number of times
    #>
    param(
        [Parameter(Mandatory)][int]$ExitCode,
        [Parameter(Mandatory)][AllowEmptyString()][string]$Transcript,
        [AllowEmptyString()][string]$ResultSubtype = "",
        [bool]$ResultIsError = $false
    )

    # Only the error channels are scanned, never Claude's narration.
    $text = Get-SignalText -Transcript $Transcript

    # A usage limit can be reported even on a zero exit code, so this is checked
    # before the success path.
    $hit = Test-AnyPattern -Text $text -Patterns $script:UsageLimitPatterns
    if ($null -ne $hit) {
        return @{
            Category = 'usage_limit'
            Reason   = 'Claude reported a usage or rate limit'
            ResetAt  = (Get-ResetTime -Text $text)
            Pattern  = $hit
        }
    }

    $hit = Test-AnyPattern -Text $text -Patterns $script:AuthPatterns
    if ($null -ne $hit) {
        return @{ Category = 'auth_error'; Reason = 'Claude reported an authentication problem'; ResetAt = $null; Pattern = $hit }
    }

    if ($ExitCode -eq 0 -and -not $ResultIsError) {
        return @{ Category = 'success'; Reason = 'Claude completed the turn'; ResetAt = $null; Pattern = $null }
    }

    if ($ResultSubtype -eq 'error_max_turns') {
        return @{ Category = 'max_turns'; Reason = 'Turn ceiling reached with work outstanding'; ResetAt = $null; Pattern = $null }
    }

    $hit = Test-AnyPattern -Text $text -Patterns $script:NetworkPatterns
    if ($null -ne $hit) {
        return @{ Category = 'network_error'; Reason = 'Transient network or upstream failure'; ResetAt = $null; Pattern = $hit }
    }

    return @{
        Category = 'crash'
        Reason   = "Claude exited unexpectedly with code $ExitCode"
        ResetAt  = $null
        Pattern  = $null
    }
}
