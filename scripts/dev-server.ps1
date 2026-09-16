<#
.SYNOPSIS
    Boots a local Paper, Folia or Spigot server with the freshly built plugin installed.

.DESCRIPTION
    The Windows PowerShell counterpart of scripts/dev-server.sh, for manual testing of
    session tracking, reminders and the /spulse command. Same run/ directory, same cached
    server jar names, same server.properties, so the two scripts can be used
    interchangeably on one checkout.

    Written for Windows PowerShell 5.1, which is what ships with Windows: no ternaries, no
    null-coalescing, no && chains. Resolves the server through PaperMC's v3 "fill" API and
    verifies the download against the SHA-256 the API publishes before executing it.

    Spigot publishes no server jar, so for spigot the first run builds one with BuildTools:
    it needs git on PATH, takes 10-25 minutes and about 2GB under run\buildtools, and the
    jar it leaves at run\spigot-<Version>.jar is reused after that. Delete the jar to
    rebuild it.

    run\ persists across platform and version switches, and that is what makes difficulty
    awkward: difficulty=peaceful is forced into run\server.properties on every boot, but a
    world generated earlier keeps its difficulty in level.dat and ignores the property
    (verified on Paper 26.2 - the property fixes NEW worlds only). -Fresh deletes
    run\world* so the next boot generates one that honours it, and -Op writes run\ops.json
    so /difficulty peaceful can be typed in game against the world that is already there.

.PARAMETER Platform
    paper (default), folia or spigot.

.PARAMETER Version
    Minecraft version, default 1.20.4.

.PARAMETER Op
    Minecraft name to op at level 4 in run\ops.json, overwritten on every run. The UUID is
    the offline one: RFC-4122 v3 (MD5) over the UTF-8 bytes of "OfflinePlayer:<name>",
    which is what UUID.nameUUIDFromBytes gives the server in offline mode.

.PARAMETER Fresh
    Delete run\world* before booting, so the world is generated again at peaceful.

.EXAMPLE
    .\scripts\dev-server.ps1 -Platform folia -Version 1.21.11

.EXAMPLE
    .\scripts\dev-server.ps1 -Op TheGoldenDragon -Fresh
#>
[CmdletBinding()]
param(
    [ValidateSet('paper', 'folia', 'spigot')]
    [string]$Platform = 'paper',
    [string]$Version = '1.20.4',
    # Mirrors the [A-Za-z0-9_]{1,16} name check dev-server.sh does on --op.
    [ValidatePattern('^[A-Za-z0-9_]{1,16}$')]
    [string]$Op,
    [switch]$Fresh
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
if (-not (Test-Path $ServerJar) -and $Platform -eq 'spigot') {
    # BuildTools clones Spigot's repositories with git and fails a long way in without it,
    # so check first.
    if ($null -eq (Get-Command git -ErrorAction SilentlyContinue)) {
        throw 'building spigot needs git on PATH'
    }
    Write-Host "==> Building spigot $Version with BuildTools (10-25 minutes on the first run)"
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $BtBuild = (Invoke-RestMethod -Uri 'https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/api/json?tree=number' -UseBasicParsing).number
    if ($null -eq $BtBuild) {
        throw 'could not resolve the latest BuildTools build'
    }
    $BtDir = Join-Path $RunDir 'buildtools'
    $BtJar = Join-Path $BtDir "BuildTools-$BtBuild.jar"
    New-Item -ItemType Directory -Force -Path $BtDir | Out-Null
    if (-not (Test-Path $BtJar)) {
        Invoke-WebRequest -Uri "https://hub.spigotmc.org/jenkins/job/BuildTools/$BtBuild/artifact/target/BuildTools.jar" -OutFile "$BtJar.tmp" -UseBasicParsing
        Move-Item -Force "$BtJar.tmp" $BtJar
    }
    Write-Host "    BuildTools build $BtBuild"
    Push-Location $BtDir
    try {
        & java -jar $BtJar --rev $Version --compile SPIGOT --output-dir $RunDir --final-name "spigot-$Version.jar"
        if ($LASTEXITCODE -ne 0) {
            throw "BuildTools failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
    if (-not (Test-Path $ServerJar)) {
        throw "BuildTools finished without producing $ServerJar"
    }
}
elseif (-not (Test-Path $ServerJar)) {
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
if ($Fresh) {
    # world, world_nether and world_the_end on all three platforms. A non-default
    # level-name is not covered by the wildcard and has to be deleted by hand; handling it
    # would mean parsing the very file this script is about to rewrite.
    Write-Host '==> Removing generated worlds'
    Get-ChildItem -Path $RunDir -Filter 'world*' -Directory -ErrorAction SilentlyContinue |
        ForEach-Object {
            Write-Host "    $($_.Name)"
            Remove-Item -Recurse -Force $_.FullName
        }
}

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
difficulty=peaceful
'@
    [IO.File]::WriteAllText($Props, ($Text -replace "`r`n", "`n") + "`n")
}

# Forced on every boot rather than only on the first: the heredoc above is write-once, and
# the server rewrites the whole file at shutdown, so an existing run\ would otherwise keep
# whatever difficulty it was left with. Only this one key is touched.
#
# A server-touched server.properties is CRLF throughout, and `.*$` swallows the CR, so the
# rewritten difficulty line alone comes back LF in an otherwise CRLF file.
# java.util.Properties does not care - it accepts either terminator.
#
# A commented-out #difficulty=... is deliberately not matched by the `^difficulty=` anchor:
# the real key is appended instead and the comment is left exactly as it was.
$PropsText = [IO.File]::ReadAllText($Props)
if ($PropsText -match '(?m)^difficulty=') {
    $PropsText = $PropsText -replace '(?m)^difficulty=.*$', 'difficulty=peaceful'
}
else {
    # A file whose last character is not a newline would glue the key onto the previous
    # value, so prepend one.
    if ($PropsText.Length -gt 0 -and -not $PropsText.EndsWith("`n")) {
        $PropsText = $PropsText + "`n"
    }
    $PropsText = $PropsText + "difficulty=peaceful`n"
}
[IO.File]::WriteAllText($Props, $PropsText)

if (-not [string]::IsNullOrEmpty($Op)) {
    # The offline UUID the server derives for an unauthenticated join:
    # UUID.nameUUIDFromBytes("OfflinePlayer:<name>"), i.e. RFC-4122 v3 - MD5 over the UTF-8
    # bytes with no namespace prefix, byte 6 forced to version 3 and byte 8 to the RFC 4122
    # variant. Not [guid]::new($Bytes): .NET reads the first three fields little-endian and
    # would hand back a different UUID from the one the server computes.
    $Md5 = [Security.Cryptography.MD5]::Create()
    try {
        $Bytes = $Md5.ComputeHash([Text.Encoding]::UTF8.GetBytes("OfflinePlayer:$Op"))
    }
    finally {
        $Md5.Dispose()
    }
    $Bytes[6] = [byte](($Bytes[6] -band 0x0f) -bor 0x30)
    $Bytes[8] = [byte](($Bytes[8] -band 0x3f) -bor 0x80)
    $Hex = -join ($Bytes | ForEach-Object { $_.ToString('x2') })
    $OpUuid = $Hex -replace '^(.{8})(.{4})(.{4})(.{4})(.{12})$', '$1-$2-$3-$4-$5'
    # Overwritten every run, so any other operator in the file is dropped, and a server
    # still running against run\ rewrites ops.json at shutdown over the top of this.
    # bypassesPlayerLimit is false to mirror exactly what the server itself writes.
    $OpsText = @"
[
  {
    "uuid": "$OpUuid",
    "name": "$Op",
    "level": 4,
    "bypassesPlayerLimit": false
  }
]
"@
    [IO.File]::WriteAllText((Join-Path $RunDir 'ops.json'), ($OpsText -replace "`r`n", "`n") + "`n")
}

Copy-Item -Force $PluginJar.FullName (Join-Path $RunDir 'plugins')

# ---------------------------------------------------------------------------
# Run
# ---------------------------------------------------------------------------
Write-Host ''
Write-Host "==> Starting $Platform $Version. Type ``stop`` to shut down."
Write-Host '    difficulty is forced to peaceful in run\server.properties, but a world that'
Write-Host '    already exists keeps its own difficulty in level.dat and ignores the property.'
Write-Host '    -Fresh deletes run\world* so the next world honours it.'
if (-not [string]::IsNullOrEmpty($Op)) {
    Write-Host "    $Op is op (level 4) via run\ops.json, so /difficulty peaceful works in game."
}
Write-Host ''

Set-Location $RunDir
# A BuildTools jar here is never refreshed, so CraftBukkit soon calls it outdated and
# sleeps 20 seconds on every boot. The flag skips that, as smoke-test.sh does.
$JvmFlags = @()
if ($Platform -eq 'spigot') { $JvmFlags += '-DIReallyKnowWhatIAmDoingISwear' }
& java -Xms1G -Xmx2G @JvmFlags -jar $ServerJar --nogui
exit $LASTEXITCODE
