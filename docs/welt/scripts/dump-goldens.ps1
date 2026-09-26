# dump-goldens.ps1 - Compilation et execution des dumpers golden standalone
#
# Usage (depuis n'importe ou, les chemins sont resolus depuis l'emplacement du script) :
#   powershell -ExecutionPolicy Bypass -File docs\welt\scripts\dump-goldens.ps1
#
# Comportement :
#   - Si des fichiers docs/welt/golden-tools/*.java existent : compilation javac UTF-8 / --release 17
#     (JDK 21, classpath Utils-2.2.0.jar, sortie vers un dossier temporaire) puis execution
#     de chaque classe avec java (classpath temp + Utils-2.2.0.jar).
#   - Sinon : affiche "dumpers pas encore presents (agents G2/G3)" et sort proprement (exit 0).
#
# Ce script NE CREE PAS les dumpers eux-memes (propriete des agents G2/G3/G7).
#
# Hypotheses documentees :
#   - Seuls les dumpers connus sont executes, avec leur chemin de sortie explicite.
#   - VerifyGolden rejoue les fichiers produits puis le fixture canonique existant.
#   - --release 17 obligatoire (projet Java 17, JDK 21 utilise comme compilateur, charte section 2).

$ErrorActionPreference = 'Stop'

# --- Resolution des chemins : docs/welt/scripts -> 1 niveau de parent -> docs/welt
$scriptDir = $PSScriptRoot
$weltDir   = (Get-Item $scriptDir).Parent.FullName
$repoRoot  = (Get-Item $scriptDir).Parent.Parent.Parent.FullName

$goldenDir = Join-Path $weltDir 'golden-tools'
$utilsJar  = Join-Path $weltDir 'reference\Utils-2.2.0.jar'

# --- Detection des dumpers
$javaFiles = @()
if (Test-Path $goldenDir) {
    $javaFiles = @(Get-ChildItem -Path $goldenDir -Filter '*.java' -File)
}

if ($javaFiles.Count -eq 0) {
    Write-Host 'dumpers pas encore presents (agents G2/G3)'
    exit 0
}

if (-not (Test-Path $utilsJar)) {
    Write-Error "Utils-2.2.0.jar introuvable : $utilsJar"
    exit 1
}

# --- Toolchain Java : JDK 17+ (compilation cible Java 17)
$jdkBins = @()
if ($env:JAVA_HOME) { $jdkBins += (Join-Path $env:JAVA_HOME 'bin') }
$jdkBins += (Join-Path $env:USERPROFILE '.jdks\jdk-17.0.12\bin')
$jdkBins += (Join-Path $env:USERPROFILE '.jdks\ms-21.0.9\bin')
$jdkBins += (Join-Path $env:ProgramFiles 'Java\jdk-17\bin')
$jdkBin = $jdkBins | Where-Object { Test-Path (Join-Path $_ 'javac.exe') } | Select-Object -First 1
if (-not $jdkBin) {
    $javacCommand = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javacCommand) { $jdkBin = Split-Path $javacCommand.Source }
}
if (-not $jdkBin) { Write-Error 'JDK/Javac introuvable; installez ou exposez un JDK 17+.'; exit 1 }
$javacExe = Join-Path $jdkBin 'javac.exe'
$javaExe = Join-Path $jdkBin 'java.exe'

# --- Dossier temporaire, nettoye en fin de script
$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("welt-goldens-" + [guid]::NewGuid().ToString('N'))
$failed = 0

try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

    # Compilation de tous les .java ensemble (les dumpers peuvent se referencer entre eux)
    Write-Host "Compilation de $($javaFiles.Count) dumper(s) vers $tempDir..."
    & $javacExe -encoding UTF-8 --release 17 -cp $utilsJar -d $tempDir @($javaFiles | ForEach-Object { $_.FullName })
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Echec de la compilation javac (code $LASTEXITCODE)."
        exit 1
    }

    # Dumper outputs are written to their canonical workspace files.
    $fullCp = "$tempDir;$utilsJar"
    $goldenDir = Join-Path $repoRoot 'welt-native\golden'
    $dumpers = @(
        @{ Class = 'DumpGoldenNoise'; Output = (Join-Path $goldenDir 'perlin-golden.txt') },
        @{ Class = 'DumpGoldenRandom'; Output = (Join-Path $goldenDir 'java-random-golden.txt') },
        @{ Class = 'DumpGoldenRandomEdge'; Output = (Join-Path $goldenDir 'java-random-edge.txt') }
    )
    foreach ($dumper in $dumpers) {
        Write-Host "Generation : $($dumper.Output)"
        & $javaExe -cp $fullCp $dumper.Class $dumper.Output
        if ($LASTEXITCODE -ne 0) { throw "$($dumper.Class) a echoue (code $LASTEXITCODE)." }
    }
    $verifyFiles = @(
        (Join-Path $goldenDir 'perlin-golden.txt'),
        (Join-Path $goldenDir 'java-random-golden.txt'),
        (Join-Path $goldenDir 'java-random-edge.txt'),
        (Join-Path $weltDir 'golden-tools\example-golden.txt')
    ) | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf }
    Write-Host 'Verification des golden Java...'
    & $javaExe -cp $fullCp VerifyGolden @verifyFiles
    if ($LASTEXITCODE -ne 0) { throw "VerifyGolden a echoue (code $LASTEXITCODE)." }
    Write-Host "Termine : $($dumpers.Count) fichiers regenes et $($verifyFiles.Count) fichiers verifies."
    exit 0
}
finally {
    if (Test-Path $tempDir) {
        Remove-Item -Path $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}
