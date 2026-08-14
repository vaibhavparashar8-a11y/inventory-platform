# Process.ps1 — run one Claude Code attempt and stream its progress.
#
# The prompt goes in over stdin rather than as an argument: it is long, contains
# quotes and backticks, and command-line quoting through PowerShell to a Node
# executable is a reliable source of silent corruption.
#
# Output is stream-json so the runner can show meaningful progress lines instead
# of a frozen terminal, and so the final result object (subtype, is_error,
# session id) is available for classification.

Set-StrictMode -Version Latest

function Get-ClaudeArguments {
    param([Parameter(Mandatory)][hashtable]$Config)

    $arguments = @('-p', '--output-format', 'stream-json', '--verbose')

    $model = Get-RunnerSetting -Config $Config -Section 'claude' -Key 'model' -Default ''
    if ($model) { $arguments += @('--model', $model) }

    $permissionMode = Get-RunnerSetting -Config $Config -Section 'claude' -Key 'permission_mode' -Default 'acceptEdits'
    $arguments += @('--permission-mode', $permissionMode)

    $allowed = Get-RunnerSetting -Config $Config -Section 'claude' -Key 'allowed_tools' -Default @()
    if ($allowed -and $allowed.Count -gt 0) { $arguments += @('--allowedTools') + $allowed }

    $disallowed = Get-RunnerSetting -Config $Config -Section 'claude' -Key 'disallowed_tools' -Default @()
    if ($disallowed -and $disallowed.Count -gt 0) { $arguments += @('--disallowedTools') + $disallowed }

    $maxTurns = Get-RunnerSetting -Config $Config -Section 'claude' -Key 'max_turns' -Default 200
    $arguments += @('--max-turns', "$maxTurns")

    return $arguments
}

function Format-StreamEvent {
    <#
      Turns one stream-json line into a short human progress line, or $null when
      the event is not worth showing. Also returns any text worth scanning for a
      usage-limit signal.
    #>
    param([Parameter(Mandatory)][string]$Line)

    $result = @{ Display = $null; Text = $Line; Subtype = $null; IsError = $false; IsResult = $false }

    $event = $null
    try { $event = $Line | ConvertFrom-Json } catch { return $result }
    if ($null -eq $event) { return $result }
    if (-not ($event.PSObject.Properties.Name -contains 'type')) { return $result }

    switch ($event.type) {
        'system' {
            if ($event.PSObject.Properties.Name -contains 'subtype' -and $event.subtype -eq 'init') {
                $result.Display = "  session $($event.session_id)"
            }
        }
        'assistant' {
            foreach ($block in $event.message.content) {
                if ($block.type -eq 'text' -and $block.text.Trim()) {
                    $snippet = ($block.text -split "`n" | Where-Object { $_.Trim() } | Select-Object -First 1)
                    if ($snippet.Length -gt 160) { $snippet = $snippet.Substring(0, 160) + '...' }
                    $result.Display = "  $snippet"
                }
                elseif ($block.type -eq 'tool_use') {
                    $detail = ''
                    if ($block.input.PSObject.Properties.Name -contains 'command') { $detail = $block.input.command }
                    elseif ($block.input.PSObject.Properties.Name -contains 'file_path') { $detail = $block.input.file_path }
                    if ($detail.Length -gt 120) { $detail = $detail.Substring(0, 120) + '...' }
                    $result.Display = "  - $($block.name) $detail".TrimEnd()
                }
            }
        }
        'result' {
            $result.IsResult = $true
            if ($event.PSObject.Properties.Name -contains 'subtype') { $result.Subtype = $event.subtype }
            if ($event.PSObject.Properties.Name -contains 'is_error') { $result.IsError = [bool]$event.is_error }
            $result.Display = "  result: $($result.Subtype)"
        }
    }

    return $result
}

function ConvertTo-CommandLineArgument {
    <#
      Windows PowerShell 5.1 runs on .NET Framework, which has no
      ProcessStartInfo.ArgumentList — the command line has to be built and
      quoted by hand, following the rules the CRT uses to split it again.
    #>
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Value)

    if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') { return $Value }

    $escaped = New-Object System.Text.StringBuilder
    [void]$escaped.Append('"')
    $backslashes = 0
    foreach ($ch in $Value.ToCharArray()) {
        if ($ch -eq '\') { $backslashes++; continue }
        if ($ch -eq '"') {
            [void]$escaped.Append('\', ($backslashes * 2) + 1).Append('"')
        }
        else {
            if ($backslashes -gt 0) { [void]$escaped.Append('\', $backslashes) }
            [void]$escaped.Append($ch)
        }
        $backslashes = 0
    }
    if ($backslashes -gt 0) { [void]$escaped.Append('\', $backslashes * 2) }
    [void]$escaped.Append('"')
    return $escaped.ToString()
}

function Resolve-ClaudeLauncher {
    <#
      npm installs `claude` on Windows as a .cmd shim. CreateProcess cannot run
      a .cmd directly, so those are launched through the command interpreter.
      Returns the executable and the prefix its arguments need.
    #>
    param([Parameter(Mandatory)][string[]]$Arguments)

    # `Get-Command claude` resolves to claude.ps1 first, which CreateProcess
    # cannot execute. npm also installs claude.cmd and an extensionless shell
    # script beside it; the .cmd (or a real .exe) is the one that can be started.
    $candidates = @(Get-Command claude -All -ErrorAction SilentlyContinue)
    $command = $candidates | Where-Object { $_.Source -match '\.exe$' } | Select-Object -First 1
    if ($null -eq $command) {
        $command = $candidates | Where-Object { $_.Source -match '\.(cmd|bat)$' } | Select-Object -First 1
    }
    if ($null -eq $command) {
        throw "No runnable 'claude' found on PATH (need claude.exe or claude.cmd). Install it with: npm i -g @anthropic-ai/claude-code"
    }

    $source = $command.Source
    $quoted = ($Arguments | ForEach-Object { ConvertTo-CommandLineArgument -Value $_ }) -join ' '

    if ($source -match '\.(cmd|bat)$') {
        # cmd.exe /c "" "<shim>" args "" keeps a quoted path intact.
        return @{
            FileName    = $env:ComSpec
            CommandLine = "/c `"`"$source`" $quoted`""
        }
    }
    return @{ FileName = $source; CommandLine = $quoted }
}

function Invoke-ClaudeAttempt {
    <#
      Runs Claude once. Returns ExitCode, Transcript (everything seen on stdout
      and stderr), the result subtype, and whether the result was an error.
      Never throws on a non-zero exit — classification is the caller's job.
    #>
    param(
        [Parameter(Mandatory)][string]$Prompt,
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [int]$TimeoutMinutes = 0
    )

    $launcher = Resolve-ClaudeLauncher -Arguments $Arguments

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $launcher.FileName
    $psi.Arguments = $launcher.CommandLine
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $psi

    $stderrBuffer = New-Object System.Text.StringBuilder
    $stderrHandler = {
        if ($null -ne $EventArgs.Data) { [void]$Event.MessageData.Append($EventArgs.Data).Append("`n") }
    }
    $subscription = Register-ObjectEvent -InputObject $process -EventName ErrorDataReceived `
        -Action $stderrHandler -MessageData $stderrBuffer

    $transcript = New-Object System.Text.StringBuilder
    $subtype = ''
    $isError = $false
    $deadline = $null
    if ($TimeoutMinutes -gt 0) { $deadline = (Get-Date).AddMinutes($TimeoutMinutes) }

    try {
        [void]$process.Start()
        $process.BeginErrorReadLine()

        $process.StandardInput.Write($Prompt)
        $process.StandardInput.Close()

        while (-not $process.StandardOutput.EndOfStream) {
            $line = $process.StandardOutput.ReadLine()
            if ($null -eq $line) { break }
            [void]$transcript.Append($line).Append("`n")

            $formatted = Format-StreamEvent -Line $line
            if ($formatted.Display) { Write-RunnerLog -Message $formatted.Display -Level 'claude' }
            if ($formatted.IsResult) {
                if ($formatted.Subtype) { $subtype = $formatted.Subtype }
                $isError = $formatted.IsError
            }

            if ($null -ne $deadline -and (Get-Date) -gt $deadline) {
                Write-RunnerLog -Message "Attempt exceeded $TimeoutMinutes minutes; stopping it cleanly." -Level 'warn'
                try { $process.Kill() } catch { Write-RunnerLog -Message "Could not stop the process: $_" -Level 'warn' }
                break
            }
        }

        $process.WaitForExit()
        $exitCode = $process.ExitCode
    }
    finally {
        Unregister-Event -SubscriptionId $subscription.Id -ErrorAction SilentlyContinue
        Remove-Job -Id $subscription.Id -Force -ErrorAction SilentlyContinue
        $process.Dispose()
    }

    $stderrText = $stderrBuffer.ToString()
    if ($stderrText.Trim()) { [void]$transcript.Append($stderrText) }

    return @{
        ExitCode   = $exitCode
        Transcript = $transcript.ToString()
        Subtype    = $subtype
        IsError    = $isError
        Stderr     = $stderrText
    }
}
