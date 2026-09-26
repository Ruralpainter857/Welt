# rebuild-native.ps1 - Reconstruction et deploiement de la bibliotheque native.
#
# Usage (depuis n'importe ou, les chemins sont resolus depuis l'emplacement du script) :
#   powershell -ExecutionPolicy Bypass -File docs\welt\scripts\rebuild-native.ps1
#
# Etapes :
#   1. Utilise le cargo du PATH, de CARGO_HOME, ou l'installation rustup par defaut.
#   2. cargo build --release sur le workspace welt-native (manifest resolu relativement au script).
#   3. Copie la bibliotheque vers la ressource native de la plateforme courante.
#
# Aucun cargo clean, git, ni installation d'outil. RUSTUP_HOME, CARGO_HOME et
# CARGO_TARGET_DIR sont respectes s'ils ont ete definis par l'utilisateur.

$ErrorActionPreference = 'Stop'

# --- Resolution des chemins : docs/welt/scripts -> 3 niveaux de parents -> racine du depot
$scriptDir = $PSScriptRoot
$repoRoot  = (Get-Item $scriptDir).Parent.Parent.Parent.FullName

$manifestPath = Join-Path $repoRoot 'welt-native\Cargo.toml'
if (-not (Test-Path $manifestPath)) {
    Write-Error "Manifest introuvable : $manifestPath (verifier le layout du depot)"
    exit 1
}

# --- Localisation portable de Cargo : PATH, CARGO_HOME, puis installation rustup par defaut
$cargoCommand = Get-Command cargo -CommandType Application -ErrorAction SilentlyContinue
if ($cargoCommand) {
    $cargoExe = $cargoCommand.Source
} else {
    $cargoHome = if ($env:CARGO_HOME) { $env:CARGO_HOME } else { Join-Path $env:USERPROFILE '.cargo' }
    $cargoExe = Join-Path $cargoHome 'bin\cargo.exe'
    if (-not (Test-Path -LiteralPath $cargoExe -PathType Leaf)) {
        $toolsRust = Join-Path $env:SystemDrive 'tools\rust'
        $toolsCargo = Join-Path $toolsRust 'cargo\bin\cargo.exe'
        if (Test-Path -LiteralPath $toolsCargo -PathType Leaf) {
            $cargoExe = $toolsCargo
            if ((-not $env:RUSTUP_HOME) -and (Test-Path (Join-Path $toolsRust 'rustup'))) {
                $env:RUSTUP_HOME = Join-Path $toolsRust 'rustup'
            }
            if (-not $env:CARGO_HOME) {
                $env:CARGO_HOME = Join-Path $toolsRust 'cargo'
            }
        }
    }
}
if (-not (Test-Path -LiteralPath $cargoExe -PathType Leaf)) {
    Write-Error "Cargo introuvable. Installez Rust avec rustup ou ajoutez son dossier bin au PATH. Chemin verifie : $cargoExe"
    exit 1
}

# --- Plateforme cible pour le chemin de ressource NativeLoader
if ($env:OS -ne 'Windows_NT') {
    Write-Error 'Ce script PowerShell copie uniquement une bibliotheque Windows. Sur Linux/macOS, utilisez le profil Maven -Pnative.'
    exit 1
}
$nativeArch = switch ($env:PROCESSOR_ARCHITECTURE.ToLowerInvariant()) {
    'amd64' { 'x86_64' }
    'arm64' { 'aarch64' }
    default { Write-Error "Architecture Windows non prise en charge : $env:PROCESSOR_ARCHITECTURE"; exit 1 }
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
$dllDest = Join-Path $repoRoot ("WorldPainter\WPCore\src\main\resources\natives\windows-{0}\welt_core.dll" -f $nativeArch)
$destDir = Split-Path $dllDest -Parent
if (-not (Test-Path $destDir)) {
    New-Item -ItemType Directory -Force -Path $destDir | Out-Null
}
Copy-Item -Path $dllSource -Destination $dllDest -Force

$slicesSource = Join-Path (Split-Path $dllSource -Parent) 'welt_slices.dll'
if (-not (Test-Path $slicesSource)) {
    Write-Error "DLL introuvable apres build reussi : $slicesSource"
    exit 1
}
$slicesDest = Join-Path $destDir 'welt_slices.dll'
Copy-Item -Path $slicesSource -Destination $slicesDest -Force

Write-Host "OK : welt_core.dll et welt_slices.dll deployes vers $destDir"
exit 0
