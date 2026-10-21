[CmdletBinding()]
# Exemple : .\tools\build-welt.ps1 -Native -RustToolchain 1.98.1-x86_64-pc-windows-gnu -Goal package
param(
    [ValidateSet('compile', 'test', 'package', 'verify')]
    [string] $Goal = 'compile',
    [switch] $Native,
    [string] $RustToolchain,
    [switch] $SkipTests,
    [string[]] $MavenArguments = @()
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$toolsRoot = Join-Path $repoRoot 'target/build-tools'
$mavenVersion = '3.9.9'
$mavenCmd = Join-Path $toolsRoot "apache-maven-$mavenVersion/bin/mvn.cmd"

function Show-SafeOutput {
    process {
        $safeLine = $_.ToString() -replace 'C:[/\\]Users[/\\][^/\\]+', '<user>'
        if ($env:USERNAME) { $safeLine = $safeLine -replace [regex]::Escape($env:USERNAME), '<user>' }
        $safeLine
    }
}

try {
    if (!(Get-Command java -ErrorAction SilentlyContinue)) {
        throw 'Java doit être disponible dans le PATH. Configurer aussi le toolchain JDK 17 décrit dans BUILDING.md.'
    }
    if (!(Test-Path -LiteralPath $mavenCmd)) {
        New-Item -ItemType Directory -Path $toolsRoot -Force | Out-Null
        $archive = Join-Path $toolsRoot "apache-maven-$mavenVersion-bin.zip"
        $url = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$mavenVersion/apache-maven-$mavenVersion-bin.zip"
        Write-Output "Téléchargement de Maven $mavenVersion depuis Maven Central."
        Invoke-WebRequest -Uri $url -OutFile $archive
        $expected = (Invoke-WebRequest -Uri ($url + '.sha512')).Content.Trim()
        if ((Get-FileHash -LiteralPath $archive -Algorithm SHA512).Hash -ne $expected) {
            throw 'La somme SHA-512 de Maven ne correspond pas. Archive non exécutée.'
        }
        Expand-Archive -LiteralPath $archive -DestinationPath $toolsRoot -Force
    }

    if ($Native) {
        if (!(Get-Command cargo -ErrorAction SilentlyContinue)) { throw 'Cargo doit être disponible dans le PATH.' }
        $cargoArguments = @()
        if ($RustToolchain) { $cargoArguments += "+$RustToolchain" }
        $cargoArguments += @('build', '--release', '-p', 'welt-core', '-p', 'welt-slices',
                '--manifest-path', (Join-Path $repoRoot 'welt-native/Cargo.toml'))
        & cargo @cargoArguments 2>&1 | Show-SafeOutput
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        $nativeResources = Join-Path $repoRoot 'WorldPainter/WPCore/src/main/resources/natives/windows-x86_64'
        New-Item -ItemType Directory -Path $nativeResources -Force | Out-Null
        foreach ($library in @('welt_core.dll', 'welt_slices.dll')) {
            Copy-Item -LiteralPath (Join-Path $repoRoot "welt-native/target/release/$library") `
                -Destination (Join-Path $nativeResources $library) -Force
        }
    }

    $arguments = @('-B', '-ntp', '-f', (Join-Path $repoRoot 'WorldPainter/pom.xml'), $Goal)
    if ($SkipTests) { $arguments += '-DskipTests=true' }
    $arguments += $MavenArguments
    & $mavenCmd @arguments 2>&1 | Show-SafeOutput
    $buildExitCode = $LASTEXITCODE
    if ($buildExitCode -ne 0) {
        Write-Output "Build incomplete: see the Maven diagnostics above and BUILDING.md."
    }
    exit $buildExitCode
} catch {
    $_.Exception.Message | Show-SafeOutput
    exit 1
}
