# Simulate.ps1 — fake Claude attempts, so the recovery path can be proven
# without waiting for a real usage limit and without spending real quota.
#
# The simulated attempt returns exactly the shape Invoke-ClaudeAttempt returns,
# so the runner's loop, classifier, checkpoint writer and wait logic all run for
# real. Only the child process is replaced.

Set-StrictMode -Version Latest

$script:SimulationTranscripts = @{
    usage_limit_epoch    = @'
{"type":"system","subtype":"init","session_id":"sim-0001"}
{"type":"assistant","message":{"content":[{"type":"text","text":"Reading docs/DEVELOPER_GUIDE.md"}]}}
{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"git status"}}]}}
{"type":"result","subtype":"error_during_execution","is_error":true,"result":"Claude AI usage limit reached|__EPOCH__"}
'@
    usage_limit_relative = @'
{"type":"system","subtype":"init","session_id":"sim-0002"}
{"type":"assistant","message":{"content":[{"type":"text","text":"Continuing Phase 1"}]}}
{"type":"result","subtype":"error_during_execution","is_error":true,"result":"You have reached your usage limit. Try again in 2 hours 14 minutes."}
'@
    network_error        = @'
{"type":"system","subtype":"init","session_id":"sim-0003"}
{"type":"result","subtype":"error_during_execution","is_error":true,"result":"API Error: fetch failed (ECONNRESET)"}
'@
    auth_error           = @'
{"type":"system","subtype":"init","session_id":"sim-0004"}
{"type":"result","subtype":"error_during_execution","is_error":true,"result":"authentication_error: invalid API key - please run /login"}
'@
    test_failure         = @'
{"type":"system","subtype":"init","session_id":"sim-0005"}
{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"./mvnw verify"}}]}}
{"type":"assistant","message":{"content":[{"type":"text","text":"Tests failed: StockLedgerTest expected 3 but was 2. Rate limit of reservations per second is unrelated. Investigating."}]}}
{"type":"result","subtype":"success","is_error":false,"result":"Fixed the failing test and committed."}
'@
    success              = @'
{"type":"system","subtype":"init","session_id":"sim-0006"}
{"type":"assistant","message":{"content":[{"type":"text","text":"Phase 1 work continued; checkpoint updated."}]}}
{"type":"result","subtype":"success","is_error":false,"result":"Done for this attempt."}
'@
}

function Get-SimulationTranscript {
    param([Parameter(Mandatory)][string]$Name, [int]$ResetInMinutes = 3)

    if (-not $script:SimulationTranscripts.ContainsKey($Name)) {
        throw "Unknown simulation transcript: $Name"
    }
    $text = $script:SimulationTranscripts[$Name]
    if ($text -match '__EPOCH__') {
        $epoch = [System.DateTimeOffset]::new((Get-Date).AddMinutes($ResetInMinutes)).ToUnixTimeSeconds()
        $text = $text.Replace('__EPOCH__', "$epoch")
    }
    return $text
}

function Invoke-SimulatedAttempt {
    <#
      Drop-in stand-in for Invoke-ClaudeAttempt. Streams the canned transcript
      through the same formatter the real path uses, so what the user sees in
      simulation is what they will see in a real run.
    #>
    param(
        [Parameter(Mandatory)][string]$Scenario,
        [int]$ResetInMinutes = 3
    )

    Write-RunnerLog -Message "SIMULATION: running canned scenario '$Scenario' instead of Claude." -Level 'warn'
    $transcript = Get-SimulationTranscript -Name $Scenario -ResetInMinutes $ResetInMinutes

    $subtype = ''
    $isError = $false
    foreach ($line in ($transcript -split "`r?`n")) {
        if ($line.Trim().Length -eq 0) { continue }
        $formatted = Format-StreamEvent -Line $line
        if ($formatted.Display) { Write-RunnerLog -Message $formatted.Display -Level 'claude' }
        if ($formatted.IsResult) {
            if ($formatted.Subtype) { $subtype = $formatted.Subtype }
            $isError = $formatted.IsError
        }
        Start-Sleep -Milliseconds 120
    }

    $exitCode = 0
    if ($isError) { $exitCode = 1 }

    return @{
        ExitCode   = $exitCode
        Transcript = $transcript
        Subtype    = $subtype
        IsError    = $isError
        Stderr     = ''
        Simulated  = $true
    }
}

function Invoke-ClassifierSelfTest {
    <#
      Proves the classifier separates the five outcomes that matter before the
      runner is trusted with an overnight run. Returns $true when all pass.
    #>

    $cases = @(
        @{ Name = 'usage limit with epoch'; Scenario = 'usage_limit_epoch'; Expect = 'usage_limit'; ExpectReset = $true },
        @{ Name = 'usage limit, relative time'; Scenario = 'usage_limit_relative'; Expect = 'usage_limit'; ExpectReset = $true },
        @{ Name = 'transient network failure'; Scenario = 'network_error'; Expect = 'network_error'; ExpectReset = $false },
        @{ Name = 'authentication failure'; Scenario = 'auth_error'; Expect = 'auth_error'; ExpectReset = $false },
        @{ Name = 'failing test is not a limit'; Scenario = 'test_failure'; Expect = 'success'; ExpectReset = $false },
        @{ Name = 'clean completion'; Scenario = 'success'; Expect = 'success'; ExpectReset = $false }
    )

    $allPassed = $true
    foreach ($case in $cases) {
        $transcript = Get-SimulationTranscript -Name $case.Scenario
        $exitCode = 0
        if ($case.Expect -ne 'success') { $exitCode = 1 }
        $isError = ($case.Expect -ne 'success')

        $classification = Get-AttemptClassification -ExitCode $exitCode -Transcript $transcript `
            -ResultSubtype '' -ResultIsError $isError

        $passed = ($classification.Category -eq $case.Expect)
        if ($case.ExpectReset -and $null -eq $classification.ResetAt) { $passed = $false }

        if ($passed) {
            $detail = ''
            if ($null -ne $classification.ResetAt) {
                $detail = " (reset at {0:HH:mm:ss})" -f ([datetime]$classification.ResetAt)
            }
            Write-RunnerLog -Message ("  PASS  {0} -> {1}{2}" -f $case.Name, $classification.Category, $detail)
        }
        else {
            Write-RunnerLog -Message ("  FAIL  {0}: expected {1}, got {2}" -f `
                    $case.Name, $case.Expect, $classification.Category) -Level 'error'
            $allPassed = $false
        }
    }

    # A failing build must never be read as a usage limit, even when the word
    # "limit" appears in the test output. This is the misclassification that
    # would park the runner for hours over a red test.
    $noisyBuild = 'BUILD FAILURE: StockBalanceTest - expected reservation limit rejection'
    $noisy = Get-AttemptClassification -ExitCode 1 -Transcript $noisyBuild -ResultSubtype '' -ResultIsError $true
    if ($noisy.Category -eq 'usage_limit') {
        Write-RunnerLog -Message "  FAIL  build output containing 'limit' classified as a usage limit" -Level 'error'
        $allPassed = $false
    }
    else {
        Write-RunnerLog -Message "  PASS  build failure text -> $($noisy.Category)"
    }

    return $allPassed
}
