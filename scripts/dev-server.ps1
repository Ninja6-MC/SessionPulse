<#
.SYNOPSIS
    Boots a local Paper or Folia server with the freshly built plugin installed.

.DESCRIPTION
    The Windows PowerShell counterpart of scripts/dev-server.sh, for manual testing of
    session tracking, reminders and the /spulse command. Same run/ directory, same cached
    server jar names, same server.properties, so the two scripts can be used
    interchangeably on one checkout.

    Written for Windows PowerShell 5.1, which is what ships with Windows: no ternaries, no
    null-coalescing, no && chains. Resolves the server through PaperMC's v3 "fill" API and
    verifies the download against the SHA-256 the API publishes before executing it.

.PARAMETER Platform
    paper (default) or folia.

.PARAMETER Version
    Minecraft version, default 1.20.4.

.EXAMPLE
    .\scripts\dev-server.ps1 -Platform folia -Version 1.21.11
#>
[CmdletBinding()]
param(
    [ValidateSet('paper', 'folia')]
    [string]$Platform = 'paper',
    [string]$Version = '1.20.4'
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$RunDir = Join-Path $RepoRoot 'run'
$ServerJar = Join-Path $RunDir "$Platform-$Version.jar"

Set-Location $RepoRoot

# ---------------------------------------------------------------------------
# Build the plugin
# ---------------------------------------------------------------------------
Write-Host '==> Building plugin'
& .\gradlew.bat shadowJar -q
if ($LASTEXITCODE -ne 0) {
    throw "gradlew shadowJar failed with exit code $LASTEXITCODE"
}

# The exclusions are load-bearing: withSourcesJar() and withJavadocJar() are both on and
# tasks.jar is classified `thin`, so build/libs holds four jars and only one of them is
# the shaded plugin.
$PluginJar = Get-ChildItem -Path (Join-Path $RepoRoot 'build\libs') -Filter '*.jar' |
    Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-javadoc.jar' -and $_.Name -notlike '*-thin.jar' } |
    Select-Object -First 1
if ($null -eq $PluginJar) {
    throw 'no plugin jar found in build\libs'
}
Write-Host "    $($PluginJar.FullName)"

New-Item -ItemType Directory -Force -Path (Join-Path $RunDir 'plugins') | Out-Null

# ---------------------------------------------------------------------------
# Resolve and download the server jar (cached between runs)
# ---------------------------------------------------------------------------
if (-not (Test-Path $ServerJar)) {
    Write-Host "==> Resolving $Platform $Version"
    # PowerShell 5.1 defaults to TLS 1.0/1.1 on older machines; fill.papermc.io needs 1.2.
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $Api = "https://fill.papermc.io/v3/projects/$Platform/versions/$Version/builds/latest"
    $Build = Invoke-RestMethod -Uri $Api -UseBasicParsing

    # The property name carries a colon, so it is read by string rather than dotted. The
    # "server:default" download is the runnable jar, not the Mojang-mapped one.
    $Download = $Build.downloads.'server:default'
    if ($null -eq $Download -or [string]::IsNullOrEmpty($Download.url) -or [string]::IsNullOrEmpty($Download.checksums.sha256)) {
        throw "could not resolve a download for $Platform $Version"
    }

    Write-Host "    $($Download.url)"
    $Tmp = "$ServerJar.tmp"
    Invoke-WebRequest -Uri $Download.url -OutFile $Tmp -UseBasicParsing

    # The jar is downloaded and then executed, so verify it rather than trust the transfer.
    $Actual = (Get-FileHash -Path $Tmp -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($Actual -ne $Download.checksums.sha256.ToLowerInvariant()) {
        Remove-Item -Force $Tmp
        throw "checksum mismatch for $($Download.url): expected $($Download.checksums.sha256), got $Actual"
    }
    Move-Item -Force $Tmp $ServerJar
    Write-Host '    verified'
}
else {
    Write-Host "==> Using cached $(Split-Path -Leaf $ServerJar)"
}

# ---------------------------------------------------------------------------
# Server configuration
# ---------------------------------------------------------------------------
# ASCII without a BOM: the server reads these as Java properties, and 5.1's UTF8 encoding
# writes a BOM that would become part of the first key.
[IO.File]::WriteAllText((Join-Path $RunDir 'eula.txt'), "eula=true`n")

# Written once so hand-edits survive; delete the file to regenerate it.
$Props = Join-Path $RunDir 'server.properties'
if (-not (Test-Path $Props)) {
    $Text = @'
# Offline mode so arbitrary usernames can join for multi-player tracking tests.
# Local development only.
online-mode=false
level-type=minecraft\:flat
view-distance=6
simulation-distance=6
spawn-protection=0
max-players=10
motd=SessionPulse dev server
'@
    [IO.File]::WriteAllText($Props, ($Text -replace "`r`n", "`n") + "`n")
}

Copy-Item -Force $PluginJar.FullName (Join-Path $RunDir 'plugins')

# ---------------------------------------------------------------------------
# Run
# ---------------------------------------------------------------------------
Write-Host ''
Write-Host "==> Starting $Platform $Version. Type ``stop`` to shut down."
Write-Host ''

Set-Location $RunDir
& java -Xms1G -Xmx2G -jar $ServerJar --nogui
exit $LASTEXITCODE
