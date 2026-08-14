# Config.ps1 — minimal YAML reader for .claude/config.yaml.
#
# Windows PowerShell 5.1 has no YAML parser and this project deliberately has no
# npm/pip toolchain at the repository root, so the runner reads its own config.
# The supported subset is exactly what config.yaml uses: comments, two levels of
# nested maps, scalars, and flow lists ([a, b, "c d"]). Anything else is a
# configuration error and is reported as one rather than silently ignored.

Set-StrictMode -Version Latest

function ConvertFrom-RunnerYamlScalar {
    param([string]$Raw)

    $value = $Raw.Trim()
    if ($value.Length -eq 0) { return "" }

    # Flow list: [a, b, "c, d"] — quoted elements may contain commas.
    if ($value.StartsWith("[") -and $value.EndsWith("]")) {
        $inner = $value.Substring(1, $value.Length - 2)
        $items = New-Object System.Collections.Generic.List[string]
        $current = New-Object System.Text.StringBuilder
        $quote = $null
        foreach ($ch in $inner.ToCharArray()) {
            if ($null -ne $quote) {
                if ($ch -eq $quote) { $quote = $null } else { [void]$current.Append($ch) }
                continue
            }
            if ($ch -eq '"' -or $ch -eq "'") { $quote = $ch; continue }
            if ($ch -eq ',') { $items.Add($current.ToString().Trim()); [void]$current.Clear(); continue }
            [void]$current.Append($ch)
        }
        $tail = $current.ToString().Trim()
        if ($tail.Length -gt 0) { $items.Add($tail) }
        return , ($items.ToArray())
    }

    if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
        ($value.StartsWith("'") -and $value.EndsWith("'"))) {
        if ($value.Length -ge 2) { return $value.Substring(1, $value.Length - 2) }
    }

    if ($value -match '^-?\d+$') { return [int]$value }
    if ($value -eq 'true') { return $true }
    if ($value -eq 'false') { return $false }
    return $value
}

function Read-RunnerConfig {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Runner config not found: $Path"
    }

    $config = @{}
    $section = $null
    $lineNumber = 0

    foreach ($line in (Get-Content -LiteralPath $Path -Encoding UTF8)) {
        $lineNumber++
        $stripped = $line

        # Strip a trailing comment, but not a '#' inside quotes or a flow list.
        if ($stripped -match '^\s*#') { continue }
        if ($stripped.Trim().Length -eq 0) { continue }

        if ($stripped -notmatch '^(\s*)([A-Za-z0-9_]+)\s*:\s*(.*)$') {
            throw "Cannot parse ${Path}:${lineNumber}: '$line'"
        }
        $indent = $Matches[1].Length
        $key = $Matches[2]
        $rest = $Matches[3]

        # Trailing comment on a scalar line, only when clearly outside quotes.
        if ($rest -match '^\s*(.*?)\s+#\s.*$' -and $rest -notmatch '^\s*["''\[]') {
            $rest = $Matches[1]
        }

        if ($indent -eq 0) {
            if ($rest.Trim().Length -eq 0) {
                $section = $key
                $config[$section] = @{}
            }
            else {
                $section = $null
                $config[$key] = ConvertFrom-RunnerYamlScalar -Raw $rest
            }
            continue
        }

        if ($null -eq $section) {
            throw "Indented key '$key' outside any section at ${Path}:${lineNumber}"
        }
        $config[$section][$key] = ConvertFrom-RunnerYamlScalar -Raw $rest
    }

    return $config
}

function Get-RunnerSetting {
    param(
        [Parameter(Mandatory)][hashtable]$Config,
        [Parameter(Mandatory)][string]$Section,
        [Parameter(Mandatory)][string]$Key,
        $Default = $null
    )

    if (-not $Config.ContainsKey($Section)) { return $Default }
    $bag = $Config[$Section]
    if ($bag -isnot [hashtable]) { return $Default }
    if (-not $bag.ContainsKey($Key)) { return $Default }
    $value = $bag[$Key]
    if ($value -is [string] -and $value.Length -eq 0 -and $null -ne $Default) { return $Default }
    return $value
}

function Set-RunnerSetting {
    <#
      Writes a scalar back into config.yaml in place, preserving comments and
      layout. Used only for project.baseline_commit, which the runner fills in
      on the first run so the Phase 1 baseline stays recoverable.
    #>
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Section,
        [Parameter(Mandatory)][string]$Key,
        [Parameter(Mandatory)][string]$Value
    )

    $lines = Get-Content -LiteralPath $Path -Encoding UTF8
    $inSection = $false
    $written = $false

    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($line -match '^[A-Za-z0-9_]+\s*:') {
            $inSection = ($line -match "^$([regex]::Escape($Section))\s*:")
            continue
        }
        if ($inSection -and $line -match "^(\s+)$([regex]::Escape($Key))\s*:") {
            $lines[$i] = "$($Matches[1])${Key}: `"$Value`""
            $written = $true
            break
        }
    }

    if (-not $written) {
        throw "Could not locate ${Section}.${Key} in $Path to update it"
    }
    Set-Content -LiteralPath $Path -Value $lines -Encoding UTF8
}
