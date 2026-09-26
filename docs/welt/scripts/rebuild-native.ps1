# rebuild-native.ps1 - Reconstruction et deploiement de la lib native welt_core.dll
#
# Usage (depuis n'importe ou, les chemins sont resolus depuis l'emplacement du script) :
#   powershell -ExecutionPolicy Bypass -File docs\welt\scripts\rebuild-native.ps1
#
# Etapes :
#   1. Positionne l'environnement Rust (RUSTUP_HOME / CARGO_HOME, charte section 2).
#   2. cargo build --release sur le workspace welt-native (manifest resolu relativement au script).
#   3. Copie welt_core.dll vers WorldPainter/WPCore/src/main/resources/natives/windows-x86_64/
#      (chemin attendu par NativeLoader.java : natives/windows-x86_64/welt_core.dll).
#
# Hypotheses documentees :
#   - Windows x86_64 uniquement (toolchain GNU de la charte ; nom de dossier fige).
#   - Si CARGO_TARGET_DIR est positionne dans l'environnement (contournement hardlink E:),
#     la DLL est recherchee dans <CARGO_TARGET_DIR>\release\ au lieu de welt-native\target\release\.
#   - Aucun cargo clean, aucun git, aucune installation (charte section 7).

$ErrorActionPreference = 'Stop'

# --- Resolution des chemins : docs/welt/scripts -> 3 niveaux de parents -> racine du depot
$scriptDir = $PSScriptRoot
$repoRoot  = (Get-Item $scriptDir).Parent.Parent.Parent.FullName

$manifestPath = Join-Path $repoRoot 'welt-native\Cargo.toml'
if (-not (Test-Path $manifestPath)) {
    Write-Error "Manifest introuvable : $manifestPath (verifier le layout du depot)"
    exit 1
}

# --- Environnement Rust (charte section 2)
$env:RUSTUP_HOME = 'C:\tools\rust\rustup'
$env:CARGO_HOME  = 'C:\tools\rust\cargo'
$cargoExe = 'C:\tools\rust\cargo\bin\cargo.exe'
if (-not (Test-Path $cargoExe)) {
    Write-Error "cargo.exe introuvable : $cargoExe"
    exit 1
}

# --- Build release du workspace
Write-Host "Build release de welt-native (manifest : $manifestPath)..."
& $cargoExe build --release --manifest-path $manifestPath
if ($LASTEXITCODE -ne 0) {
    Write-Error "Echec du build cargo (code $LASTEXITCODE). DLL non copiee."
    exit 1
}

# --- Localisation de la DLL produite
if ($env:CARGO_TARGET_DIR) {
    $dllSource = Join-Path $env:CARGO_TARGET_DIR 'release\welt_core.dll'
} else {
    $dllSource = Join-Path $repoRoot 'welt-native\target\release\welt_core.dll'
}
if (-not (Test-Path $dllSource)) {
    Write-Error "DLL introuvable apres build reussi : $dllSource"
    exit 1
}

# --- Copie vers les resources WPCore (dossier cible cree si absent)
$dllDest = Join-Path $repoRoot 'WorldPainter\WPCore\src\main\resources\natives\windows-x86_64\welt_core.dll'
$destDir = Split-Path $dllDest -Parent
if (-not (Test-Path $destDir)) {
    New-Item -ItemType Directory -Force -Path $destDir | Out-Null
}
Copy-Item -Path $dllSource -Destination $dllDest -Force

Write-Host "OK : welt_core.dll reconstruit et deploye vers $dllDest"
exit 0
