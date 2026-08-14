# Wait.ps1 — pausing between attempts, and keeping the machine awake.
#
# The runner never guesses a reset period. It waits for the moment Claude
# reported; only when Claude reported nothing parseable does it fall back to the
# configured short retry and try again.

Set-StrictMode -Version Latest

function Enable-KeepAwake {
    <#
      Process-scoped only: the flag dies with this PowerShell process and the
      user's power plan is never modified.
    #>
    if (-not ([System.Management.Automation.PSTypeName]'ClaudeRunner.Power').Type) {
        Add-Type -Namespace ClaudeRunner -Name Power -MemberDefinition @"
[System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError = true)]
public static extern uint SetThreadExecutionState(uint esFlags);
"@
    }
    # ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_AWAYMODE_REQUIRED = 0x80000041.
    # Written as an unsigned literal: PowerShell parses 0x80000000 as a signed
    # int, which will not convert to the uint32 the API expects.
    [void][ClaudeRunner.Power]::SetThreadExecutionState([uint32]2147483713)
}

function Disable-KeepAwake {
    if (([System.Management.Automation.PSTypeName]'ClaudeRunner.Power').Type) {
        [void][ClaudeRunner.Power]::SetThreadExecutionState([uint32]2147483648)  # ES_CONTINUOUS
    }
}

function Wait-UntilTime {
    <#
      Sleeps until $Target, logging remaining time so an unattended terminal
      still shows the runner is alive. Honours Ctrl-C between ticks.
    #>
    param(
        [Parameter(Mandatory)][datetime]$Target,
        [string]$Purpose = 'usage reset',
        [int]$TickSeconds = 60
    )

    if ($Target -le (Get-Date)) {
        Write-RunnerLog -Message "No wait needed for $Purpose."
        return
    }

    Write-RunnerLog -Message ("Waiting for {0} until {1:yyyy-MM-dd HH:mm:ss} ({2})." -f `
            $Purpose, $Target, (Format-Duration -Span ($Target - (Get-Date))))

    while ((Get-Date) -lt $Target) {
        $remaining = $Target - (Get-Date)
        Write-RunnerLog -Message ("  still waiting - {0} remaining" -f (Format-Duration -Span $remaining))
        $sleep = [math]::Min($TickSeconds, [math]::Max(1, [int]$remaining.TotalSeconds))
        Start-Sleep -Seconds $sleep
    }
}

function Format-Duration {
    param([Parameter(Mandatory)][timespan]$Span)

    if ($Span.TotalSeconds -lt 60) { return ("{0}s" -f [int]$Span.TotalSeconds) }
    if ($Span.TotalHours -lt 1) { return ("{0}m" -f [int]$Span.TotalMinutes) }
    return ("{0}h {1:00}m" -f [int]$Span.TotalHours, $Span.Minutes)
}

function Get-BackoffDelay {
    <#
      Exponential backoff for transient network failures only. Retrying a
      non-idempotent operation on a schedule is how duplicates happen; a failed
      Claude turn is safe to retry because the next attempt re-reads the
      repository before doing anything.
    #>
    param(
        [Parameter(Mandatory)][int]$Attempt,
        [int]$StartSeconds = 30,
        [int]$MaxSeconds = 900
    )

    $delay = $StartSeconds * [math]::Pow(2, [math]::Max(0, $Attempt - 1))
    if ($delay -gt $MaxSeconds) { $delay = $MaxSeconds }
    # A little jitter, so a flapping upstream is not hammered on a fixed beat.
    $jitter = Get-Random -Minimum 0 -Maximum ([int][math]::Max(1, $delay * 0.1))
    return [int]($delay + $jitter)
}

function Resolve-ResumeTime {
    <#
      Turns a classification into the moment the next attempt should start.
      Reported reset time plus the safety buffer when known; the configured
      fallback otherwise. Never a hard-coded five hours.
    #>
    param(
        [Parameter(Mandatory)][hashtable]$Classification,
        [int]$BufferMinutes = 2,
        [int]$FallbackMinutes = 10
    )

    if ($null -ne $Classification.ResetAt) {
        $target = ([datetime]$Classification.ResetAt).AddMinutes($BufferMinutes)
        if ($target -le (Get-Date)) { $target = (Get-Date).AddMinutes($BufferMinutes) }
        return @{ Time = $target; Source = 'reported by Claude' }
    }
    return @{ Time = (Get-Date).AddMinutes($FallbackMinutes); Source = 'fallback, no reset time reported' }
}
