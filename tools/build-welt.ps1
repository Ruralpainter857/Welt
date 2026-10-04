[CmdletBinding()]
# Exemple : .\tools\build-welt.ps1 -Native -RustToolchain 1.98.1-x86_64-pc-windows-gnu -Goal package
param(
    [ValidateSet('compile', 'test', 'package', 'verify')]
    [string] $Goal = 'compile',
    [string] $JideDirectory,
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

    if ($JideDirectory) {
        $jideRoot = (Resolve-Path -LiteralPath $JideDirectory).Path
        [xml] $pom = Get-Content -LiteralPath (Join-Path $repoRoot 'WorldPainter/pom.xml') -Raw
        $versionNode = $pom.SelectSingleNode("//*[local-name()='properties']/*[local-name()='jide.version']")
        if (!$versionNode) { throw 'Version JIDE introuvable dans le POM.' }
        $jideVersion = $versionNode.InnerText
        foreach ($artifact in @('jide-common', 'jide-dock', 'jide-plaf-jdk7')) {
            $jar = Join-Path $jideRoot ($artifact + '.jar')
            if (!(Test-Path -LiteralPath $jar)) {
                if ($artifact -eq 'jide-plaf-jdk7') { continue }
                throw "JAR JIDE manquant : $artifact.jar"
            }
            # Les versions des JAR fournis doivent correspondre à celle du POM.
            & $mavenCmd -B -ntp 'org.apache.maven.plugins:maven-install-plugin:3.1.3:install-file' `
                "-Dfile=$jar" '-DgroupId=com.jidesoft' "-DartifactId=$artifact" `
                "-Dversion=$jideVersion" '-Dpackaging=jar' '-DgeneratePom=true' 2>&1 | Show-SafeOutput
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        }
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
        Write-Output "Build incomplet : consulter l'erreur Maven ci-dessus. Pour une dépendance JIDE manquante, utiliser -JideDirectory; voir BUILDING.md."
    }
    exit $buildExitCode
} catch {
    $_.Exception.Message | Show-SafeOutput
    exit 1
}
