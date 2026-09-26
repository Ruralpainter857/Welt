# Tests d'or (« golden files ») du fork Welt — protocole canonique

> Propriétaire : **G7 `parity-harness`**. Document de référence pour TOUS les fichiers d'or
> du portage WorldPainter Java → Rust (plan §0.6, charte `docs/welt/CHARTE-ORCHESTRATION.md` §5).
> Toute évolution du format passe par ce fichier ; un kind absent du registre ci-dessous
> n'est PAS conforme.

## 1. Principe

Un fichier d'or contient des **valeurs de référence dumpées côté Java** à partir des
**classes réelles** (jamais d'une ré-implémentation) :

- `org.pepsoft.util.PerlinNoise` / `FastPerlin` / `UnsafeRandom` / `RandomField`, chargés
  depuis le jar binaire `docs/welt/reference/Utils-2.2.0.jar` (celui utilisé par
  WorldPainter en production) ;
- `java.util.Random` du JDK (LCG 48 bits), les sources de vérité pour G2/G3 étant
  `docs/welt/reference/utils-2.2.0-sources/extracted/...`.

Le test de parité Rust (dans `welt-core`) **relit** le fichier et **rejoue la même
séquence d'appels** sur le portage, puis **compare bit-à-bit**.

**Tolérance : AUCUNE pour le bruit et le RNG** (charte §5). La comparaison porte sur les
bits bruts (`f32::to_bits()` vs `u32::from_str_radix(hex, 16)`), pas sur `==` des flottants
(distinction NaN/NaN-payload garantie). Une seule divergence = échec du test.
Pour des kinds futurs hors bruit/RNG (volets 1/2/3), toute tolérance doit être
**explicite** : déclarée dans l'entrée du registre (§4) et implémentée dans le test qui
consomme le golden — jamais implicite.

## 2. Layout & ownership

| Fichier | Rôle | Propriétaire |
|---|---|---|
| `welt-native/golden/README.md` | CE protocole (le présent fichier) | **G7** |
| `welt-native/golden/perlin-golden.txt` | PerlinNoise/FastPerlin/RandomField | **G2** |
| `welt-native/golden/java-random-golden.txt` | LCG java.util.Random | **G3** |
| `welt-native/golden/<autre>-golden.txt` | Goldens futurs (volets) : même format, kind enregistré au §4 | agent du volet |
| `docs/welt/golden-tools/VerifyGolden.java` | Vérificateur standalone Java (relit un golden + rejoue) | **G7** |
| `docs/welt/golden-tools/example-golden.txt` | Exemple minimal + fixture de test de VerifyGolden | **G7** |
| `docs/welt/golden-tools/DumpGoldenNoise.java` | Dumper du bruit (écrit `perlin-golden.txt`) | **G2** |
| `docs/welt/golden-tools/DumpGoldenRandom.java` | Dumper du RNG (écrit `java-random-golden.txt`) | **G3** |
| `docs/welt/scripts/dump-goldens.ps1` | Automatisation compile+dump+verify de tous les goldens | **A5** |
| Tests Rust lisant les goldens | Parité bit-exact côté Rust | G2 (bruit) / G3 (rng), dans leurs modules |
| JUnit de parité (vague 2) | Rejoue la comparaison depuis Maven/Java | **G7** |

Règles :
- On ne **retouche jamais** un golden à la main. Toute évolution (plus de lignes, nouveaux
  kinds, nouveaux seeds) = **re-dump complet** (§5) puis vérification `VerifyGolden`.
- Un agent ne modifie pas le golden d'un autre (charte §4). Il en propose un nouveau ou
  passe par l'orchestrateur.

## 3. Format canonique v1

- Encodage UTF-8 ; fins de ligne LF ou CRLF (parseurs tolérants).
- **Lignes d'en-tête/commentaires** : toute ligne vide ou dont le premier caractère non
  blanc est `#` est ignorée. Les dumpers DOIVENT écrire un en-tête minimal :
  `# kind registre v1 — <fichier> — généré par <Dumper> le <date>` (au moins une ligne).
- **Une donnée par ligne**, colonnes séparées par un ou plusieurs espaces/tabulations :

```
kind  seed  coord...  value
```

| Colonne | Règles |
|---|---|
| `kind` | Identifiant du registre (§4), minuscules ; les parseurs tolèrent toute casse. |
| `seed` | Entier 64 bits **signé décimal** (sortie `Long.toString` de Java). |
| `coord...` | 0 à 5 nombres selon le kind (décimaux IEEE-754 ou entiers, signés ; sémantique `Double.parseDouble`/`Float.parseFloat`/`Integer.parseInt` côté lecteur). Liste exacte au §4. |
| `value` | **Bits bruts en hexadécimal** : `Float.floatToRawIntBits` (8 chiffres) pour un float, `Double.doubleToRawDoubleBits` (16 chiffres) pour un double, écriture hexadécimale (8 chiffres) pour un int, 16 chiffres pour un long, `0`/`1` pour un booléen. Writers : minuscules, zéro-paddés. Parseurs : tolérants (casse, padding variable, préfixe `0x` accepté). |
| exception | Cas particulier : si l'appel doit lever, `value` = `EX:<nomSimpleClasse>` (ex. `EX:IllegalArgumentException`). |

Exemples de lignes :

```
# perlin2d — seed 0 — Float.floatToRawIntBits
perlin2d  0  0.0  0.0        bf800000
rand_next_int  0  0           25b56937
rand_next_double  0  0        3ff04b1e04b1e04b
```

## 4. Registre des kinds (v1)

Sémantique de rejeu :

- **Kinds bruit** : chaque ligne est un appel **indépendant** sur une instance
  `PerlinNoise(seed)` (mise en cache par seed côté vérificateur). La valeur de sortie est
  `(float)` en bits bruts — pour `perlin2d`/`perlin3d` cela inclut le facteur
  `FACTOR_2D`/`FACTOR_3D` de `PerlinNoise`.
- **Kinds RNG** : un **run** = suite **contiguë** de lignes de même `kind` et même `seed`,
  avec `index` strictement croissant `0, 1, 2, …`. Chaque run repart d'une instance
  **fraîche** (`new Random(seed)` côté Java, `JavaRandom::new(seed)` côté Rust). Tout
  changement de kind/seed, ou un index qui ne suit pas `précédent+1`, démarre un nouveau
  run (l'index doit alors repartir de 0). Les runs doivent être contigus dans le fichier :
  NE PAS entrelacer deux séquences de même kind/seed.
- `rand_next_gaussian` est **séquentiel par nature** (état interne `haveNextNextGaussian`
  en Java) : le run doit être rejoué intégralement, dans l'ordre.

| kind | coords (dans l'ordre) | `value` = bits de | Référence Java (vérité) | Dump |
|---|---|---|---|---|
| `perlin1d` | `x` | `Float.floatToRawIntBits(noise.getPerlinNoise(x))` | `PerlinNoise` (jar Utils 2.2.0) | G2 |
| `perlin2d` | `x y` | `…getPerlinNoise(x, y)` | idem | G2 |
| `perlin3d` | `x y z` | `…getPerlinNoise(x, y, z)` | idem | G2 |
| `promillage` | `p` (seed ignoré, **doit valoir 0**) | `…PerlinNoise.getLevelForPromillage(p)` (statique) | idem | G2 |
| `fastperlin1d` | `x` | `Float.floatToRawIntBits(sampleResult(x))` | `FastPerlin` | G2 (optionnel) |
| `fastperlin2d` | `x y` | `…sampleResult(x, y)` | `FastPerlin` | G2 (optionnel) |
| `fastperlin3d` | `x y z` | `…sampleResult(x, y, z)` | `FastPerlin` | G2 (optionnel) |
| `randomfield2d` | `bits scale x y` | `Integer` (hex 8 chiffres) de `field.getValue(x, y)` | `RandomField(int bits, double scale, long seed)` | G2 (optionnel) |
| `randomfield3d` | `bits scale x y z` | `Integer` de `getValue(x, y, z)` | idem | G2 (optionnel) |
| `rand_next_int` | `i` | `Integer` (hex 8 chiffres) de `nextInt()` | `java.util.Random` (JDK) | G3 |
| `rand_next_int_bound` | `i bound` | `Integer` de `nextInt(bound)` | idem | G3 |
| `rand_next_double` | `i` | `Double.doubleToRawLongBits(nextDouble())` | idem | G3 |
| `rand_next_gaussian` | `i` | `Double.doubleToRawLongBits(nextGaussian())` | idem | G3 |
| `rand_next_long` | `i` | `Long` (hex 16 chiffres) de `nextLong()` | idem | G3 (optionnel) |
| `rand_next_boolean` | `i` | `0`/`1` de `nextBoolean()` | idem | G3 (optionnel) |
| `rand_next_float` | `i` | `Float.floatToRawIntBits(nextFloat())` | idem | G3 (optionnel) |

`i` = index d'appel **0-based** dans le run (colonne coord n°1 pour tous les kinds RNG) ;
`bound` = argument passé à `nextInt(bound)` (colonne coord n°2) ; pour `randomfield*`,
`bits`/`scale` sont les paramètres du constructeur `RandomField(int bits, double scale, long seed)`.

⚠️ `org.pepsoft.util.UnsafeRandom` est **package-private** dans Utils 2.2.0 : un golden
dédié n'est PAS rejouable par `VerifyGolden.java` (défaut de package). Son comportement
est couvert **indirectement** par les kinds `fastperlin*` (la permutation de FastPerlin
est initialisée par UnsafeRandom). Si G2 veut un golden direct, son dumper ET un
verificateur devront déclarer `package org.pepsoft.util;` (les classes du jar ne sont pas
scellées) — à coordonner avec G7 avant d'ajouter le kind au registre.

### `rand_next_gaussian` : déterminisme indépendant du JIT

Correction après vérification du bytecode JDK 17 et 21 : `java.util.Random.nextGaussian()`
appelle `StrictMath.log` et `StrictMath.sqrt`, pas `Math.log`. L'observation précédente
sur l'intrinsic JIT de `Math.log` ne s'applique donc pas à `Random.nextGaussian()`.
Les dumps et `VerifyGolden` fonctionnent normalement, avec ou sans `-Xint` ; `-Xint`
n'est pas une exigence du protocole. Le portage Rust emploie le logarithme fdlibm et la
racine carrée IEEE-754 ; la parité native doit être confirmée par les tests Rust et CI.

Extension : un nouveau kind est ajouté par le propriétaire du dump via une PR/commit de
l'orchestrateur qui met à jour ce registre, son dumper, et son test Rust — les trois
ensemble. `VerifyGolden` doit apprendre le kind au même moment (voir son registre interne).

## 5. Procédure de re-dump (standard)

Prérequis (charte §2/§8) :
- JDK 17+ (livré : Temurin 21, compiler avec `--release 17`) :
  `C:\Users\[REDACTED]\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.9.10-hotspot\bin`.
- Jar de vérité : `E:\mc_modding\worldpainter\docs\welt\reference\Utils-2.2.0.jar`
  (⚠ E: est un subst de D: — pas de hardlinks, ne rien installer sur E:).

Étapes (à la main ; le script `docs/welt/scripts/dump-goldens.ps1` — A5 — les automatise) :

```pwsh
$javac = "C:\Users\[REDACTED]\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.9.10-hotspot\bin\javac.exe"
$java  = "C:\Users\[REDACTED]\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.9.10-hotspot\bin\java.exe"
$jar   = "E:\mc_modding\worldpainter\docs\welt\reference\Utils-2.2.0.jar"
$build = "$env:TEMP\welt-golden-classes"           # NE JAMAIS commiter de .class dans le dépôt
New-Item -ItemType Directory -Force -Path $build | Out-Null

# 1. compiler le dumper (standalone, indépendant de Maven)
& $javac --release 17 -cp $jar -d $build E:\mc_modding\worldpainter\docs\welt\golden-tools\DumpGoldenNoise.java
if ($LASTEXITCODE -ne 0) { throw "javac a échoué" }   # stderr affiché par pwsh ≠ erreur : juger sur $LASTEXITCODE

# 2. dumper (le dumper écrit directement le fichier golden)
& $java -cp "$build;$jar" DumpGoldenNoise            # ';' = séparateur de classpath Windows

# 3. vérifier le résultat — exit 0 attendu, sinon détails des divergences
& $javac --release 17 -cp $jar -d $build E:\mc_modding\worldpainter\docs\welt\golden-tools\VerifyGolden.java
& $java -cp "$build;$jar" VerifyGolden E:\mc_modding\worldpainter\welt-native\golden\perlin-golden.txt
if ($LASTEXITCODE -ne 0) { throw "VerifyGolden a échoué" }
```

Invariants :
- Le dump **exact** (mêmes seeds/coordonnes) doit rester **stable** entre deux exécutions :
  le bruit et le RNG sont déterministes — un golden qui « bouge » révèle un bug de dump.
- Le commit d'un golden est fait par **l'orchestrateur seul** (charte §7).
- Après intégration du portage Rust, le test d'or côté Rust doit être vert sur le golden
  re-dumpé : `cargo test --manifest-path E:\mc_modding\worldpainter\welt-native\Cargo.toml`.

## 6. Consommation côté Rust (G2/G3)

Dans les tests unitaires de `welt-core` (ex. `noise/perlin.rs`, `rng/java_random.rs`) :

```rust
const GOLDEN: &str = include_str!("../../../golden/perlin-golden.txt");
// parser : lignes non '#'/'vide' → kind, seed:i64, coords:[f64; N], value:u32::from_str_radix(hex, 16)
// comparaison : noise.get_perlin_noise_2d(x, y).to_bits() == expected   // tolérance AUCUNE
```

Pièges de parité documentés (charte §5) : `Math.fma` → `mul_add`, casts `(float)` → `as f32`,
promotion `i32` wrapping, `%` signé, parsing de `noiselevels.txt` avec la sémantique
`Float.parseFloat` (et non une quelconque normalisation), LCG 48 bits masqué
`& ((1<<48)-1)`.

## 7. Exemple de référence

`docs/welt/golden-tools/example-golden.txt` (G7) illustre le format : PerlinNoise 2D
seed 0 sur grille 5×5 + `java.util.Random` seed 0, 20 × `nextInt`. Le vérifier :

```pwsh
& $java -cp "$build;$jar" VerifyGolden E:\mc_modding\worldpainter\docs\welt\golden-tools\example-golden.txt
# attendu : "45 données vérifiées, 0 divergence(s)" + "RÉSULTAT : OK (bit-exact)" et exit 0
```

Voir `docs/welt/rapports/G7-parity-harness.md` pour la sortie réelle de validation.
