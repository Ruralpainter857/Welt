# dump-goldens.ps1 - Compilation et execution des dumpers golden standalone
#
# Usage (depuis n'importe ou, les chemins sont resolus depuis l'emplacement du script) :
#   powershell -ExecutionPolicy Bypass -File docs\welt\scripts\dump-goldens.ps1
#
# Comportement :
#   - Si des fichiers docs/welt/golden-tools/*.java existent : compilation javac --release 17
#     (JDK 21, classpath Utils-2.2.0.jar, sortie vers un dossier temporaire) puis execution
#     de chaque classe avec java (classpath temp + Utils-2.2.0.jar).
#   - Sinon : affiche "dumpers pas encore presents (agents G2/G3)" et sort proprement (exit 0).
#
# Ce script NE CREE PAS les dumpers eux-memes (propriete des agents G2/G3/G7).
#
# Hypotheses documentees :
#   - Chaque .java de golden-tools/ contient une classe avec main (convention du guide
#     ajouter-fonction-native.md section e) ; une erreur d'execution d'une classe est
#     affichee mais n'interrompt pas les autres.
#   - Les dumpers ecrivent eux-memes leurs sorties (vers welt-native/golden/ selon le guide) ;
#     ce script ne gere pas les fichiers d'or.
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

# --- Toolchain Java : JDK 21 (javac --release 17 pour cibler Java 17)
$jdkBin    = 'C:\Users\[REDACTED]\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.9.10-hotspot\bin'
$javacExe  = Join-Path $jdkBin 'javac.exe'
$javaExe   = Join-Path $jdkBin 'java.exe'
if (-not (Test-Path $javacExe)) {
    Write-Error "javac introuvable : $javacExe"
    exit 1
}

# --- Dossier temporaire, nettoye en fin de script
$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("welt-goldens-" + [guid]::NewGuid().ToString('N'))
$failed = 0

try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

    # Compilation de tous les .java ensemble (les dumpers peuvent se referencer entre eux)
    Write-Host "Compilation de $($javaFiles.Count) dumper(s) vers $tempDir..."
    & $javacExe --release 17 -cp $utilsJar -d $tempDir @($javaFiles | ForEach-Object { $_.FullName })
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Echec de la compilation javac (code $LASTEXITCODE)."
        exit 1
    }

    # Execution de chaque classe : FQCN = package du fichier (si present) + nom de fichier sans extension
    $fullCp = "$tempDir;$utilsJar"
    foreach ($file in $javaFiles) {
        $className = [System.IO.Path]::GetFileNameWithoutExtension($file.Name)
        $content = Get-Content -Path $file.FullName -Raw
        if ($content -match '(?m)^\s*package\s+([\w.]+)\s*;') {
            $className = "$($Matches[1]).$className"
        }
        Write-Host "Execution du dumper : $className"
        & $javaExe -cp $fullCp $className
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Echec du dumper $className (code $LASTEXITCODE) - on continue avec les autres."
            $failed++
        }
    }

    $ok = $javaFiles.Count - $failed
    Write-Host "Termine : $ok/$($javaFiles.Count) dumper(s) executes avec succes, $failed echec(s)."
    if ($failed -gt 0) { exit 1 }
    exit 0
}
finally {
    if (Test-Path $tempDir) {
        Remove-Item -Path $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}
