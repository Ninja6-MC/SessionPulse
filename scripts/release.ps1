<#
.SYNOPSIS
    Cuts a release tag, pushes it and dispatches the publisher. PowerShell wrapper
    around scripts/release.sh.

.DESCRIPTION
    The checks, the attestation prompts and the publisher dispatch live in release.sh.
    This wrapper only locates Git Bash and hands over, so there is one implementation of
    the release guard rather than two that can drift apart. See release.sh for what is
    checked and why.

    Git installs are searched before PATH because PATH may resolve to
    C:\Windows\System32\bash.exe, which launches WSL: a different filesystem view
    (/mnt/d), a different git and gh, and paths this script does not expect.

.EXAMPLE
    .\scripts\release.ps1 0.2.0-beta.2 --dry-run

.EXAMPLE
    .\scripts\release.ps1 1.0.0

.EXAMPLE
    .\scripts\release.ps1 0.2.0-beta.2 --resume hangar --candidate-run <candidate-run-id>
#>
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $ScriptArgs
)

function Find-GitBash {
    $candidates = @(
        (Join-Path $env:ProgramFiles 'Git\bin\bash.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Git\bin\bash.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Git\bin\bash.exe')
    )

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
    }

    $onPath = Get-Command bash.exe -ErrorAction SilentlyContinue
    if ($onPath -and $onPath.Source -notlike "$env:SystemRoot*") { return $onPath.Source }

    return $null
}

$bash = Find-GitBash
if (-not $bash) {
    Write-Error 'Could not find Git Bash. Install Git for Windows (https://git-scm.com/download/win), then run this again.'
    exit 1
}

$repoRoot = Split-Path -Parent $PSScriptRoot
# Forward slashes: bash.exe handles "C:/path" but treats backslashes as escapes.
# [char]92 and [char]47 avoid embedding a literal backslash in this expression.
$script = (Join-Path $PSScriptRoot 'release.sh').Replace([char]92, [char]47)

Push-Location $repoRoot
try {
    & $bash $script @ScriptArgs
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
