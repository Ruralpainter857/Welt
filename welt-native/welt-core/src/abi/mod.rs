//! ABI de données du pont Welt : le contrat C-compatible `WpTileView`
//! (G1, Phase 0 — plan §0.3, charte §5).
//!
//! # Ce que ce contrat fige
//!
//! Le pont natif ne traverse JAMAIS la frontière JNI pixel par pixel
//! (plan §2.5) : les fonctions natives reçoivent des **vues bulk** — une tuile
//! complète (`WpTileView`) ou un tableau de vues pour une zone rectangulaire
//! multi-tuiles (`*const WpTileView` + nombre). Le surcoût JNI par appel est
//! donc amorti sur 16 384 pixels minimum (~256 Ko de données brutes).
//!
//! Le layout ci-dessous est **gelé pour la version 1** (cf.
//! [`WP_TILE_VIEW_ABI_VERSION`] et les assertions statiques de tailles/offsets
//! en bas de fichier). Toute évolution : ajouter des champs À LA FIN et
//! incrémenter la version ; ne jamais réordonner ni retirer un champ.
//!
//! # Sémantique des buffers (contrat Java [`Tile`] — zones gelées, plan §1.2)
//!
//! | Champ | Type C | Contenu |
//! |---|---|---|
//! | `heights` | `u16[TILE_PIXELS]` | hauteur brute en point fixe : `float height = raw / 256f + min_height` (identique à `Tile.getHeight` ; valeurs non signées, zéro-based) |
//! | `terrain` | `u8[TILE_PIXELS]` | ordinal `Terrain.values()` (cf. `Tile.getTerrain` : `TERRAIN_VALUES[byte & 0xFF]`) |
//! | `water` | `u8[TILE_PIXELS]` | niveau d'eau brut, zéro-based : `water = raw + min_height` (cf. `Tile.getWaterLevel`) |
//! | couches bit | `u64[]` | mots de `java.util.BitSet` : bit logique *n* = bit `(n % 64)` du mot `(n / 64)`, LSB d'abord (cf. `BitSet.toLongArray`), paddé au nombre fixe de mots |
//! | couches byte | `u8[]` | `BYTE` : une valeur 0-255 par pixel ; `NIBBLE` : 2 pixels par octet (pixel pair → nibble bas `& 0x0F`, pixel impair → nibble haut `>> 4`), exactement comme `Tile.getLayerValue` |
//!
//! Indexation intra-tuile (identique à `Tile`) : `x | (y << TILE_SIZE_BITS)`
//! (cf. [`tile_index`]). Pour les couches bit-par-chunk :
//! `(x >> 4) + (y >> 4) * (TILE_SIZE >> 4)` (cf. [`bit_per_chunk_index`]).
//!
//! # Identité des couches (`layer_id`)
//!
//! `layer_id` est un identifiant opaque 32 bits interprété **par export** : la
//! convention v1 est que l'appel Java fournit, dans le même appel, la table des
//! couches (tableau d'objets `Layer` ou ordinaux convenus) dont `layer_id` est
//! l'index — l'ABI n'exige que l'unicité au sein d'un `WpTileView`. Les
//! consommateurs (G4 slice génération, G5 slice export) figeront la table
//! exacte dans leur propre contrat ; rien n'est décidé ici pour ne pas bloquer
//! leurs choix.
//!
//! `data_size` reprend les ordinaux de l'enum Java `Layer.DataSize`
//! (`BIT = 0`, `NIBBLE = 1`, `BYTE = 2`, `BIT_PER_CHUNK = 3` — cf. [`data_size`]).
//!
//! # Règles de sécurité mémoire
//!
//! - Un pointeur est déréférencé seulement si la longueur associée est > 0 ;
//!   longueur 0 ⇒ donnée absente, le pointeur peut être nul.
//! - Les buffers appartiennent à l'appelant (la JVM) : **lecture seule** en v1,
//!   valides uniquement le temps de l'appel natif ; le Rust ne les conserve
//!   jamais au-delà du retour.
//! - Endianness : mots `u64` en boutisme natif — Rust et JVM étant dans le
//!   même processus, les mots produits par `BitSet.toLongArray()` sont
//!   directement utilisables.
//!
//! # Chemin de lecture (v1)
//!
//! Lecture seule, canoniquement via `Dimension.getSnapshot()`
//! (`DimensionSnapshot`/`RODelegatingDimension`, plan §1.2) : la facade Java
//! `org.pepsoft.worldpainter.nativeapi.TileView` construit une vue read-only
//! depuis les tableaux d'un `Tile` — jamais depuis les buffers copy-on-write
//! vivants sans passer par la frontière de lecture existante.
//!
//! # Futur chemin d'écriture (documenté, non implémenté en v1)
//!
//! ⚠️ Les tableaux de `Tile` **sont** les buffers copy-on-write de l'UndoManager
//! (plan §0.3) : le natif n'écrit JAMAIS dedans directement. Le chemin
//! d'écriture sera : (1) Java bloque les events
//! (`Dimension.setEventsInhibited(true)`) ; (2) le natif calcule des résultats
//! **en bulk** dans ses propres buffers de staging ; (3) Java applique les
//! modifications via les setters existants (`Tile.setHeight`, etc.) en une
//! seule passe ; (4) `setEventsInhibited(false)` déclenche l'unique burst
//! d'events — le pattern déjà utilisé par les strokes interactifs (charte §5).
//! Les exports concernés seront ajoutés par les consommateurs (G4/G5) avec un
//! `WpTileView` mutable dédié, versionné.
//!
//! # Parallélisme
//!
//! Les fonctions natives sont pures et synchrones (plan §2.5). Pour les API
//! bulk documentées « parallel », le Rust peut paralléliser en interne
//! (scoped threads) : les vues sont immuables pendant l'appel.

use crate::error::WeltError;

// ===== Constantes du contrat (miroir de org.pepsoft.worldpainter.Constants) =====

/// Côté d'une tuile, en pixels (`Constants.TILE_SIZE`).
pub const TILE_SIZE: usize = 128;

/// Décalage pour l'indexation `x | (y << TILE_SIZE_BITS)` (`Constants.TILE_SIZE_BITS`).
pub const TILE_SIZE_BITS: u32 = 7;

/// Masque intra-tuile : `x & TILE_SIZE_MASK` (`Constants.TILE_SIZE_MASK`).
pub const TILE_SIZE_MASK: usize = TILE_SIZE - 1;

/// Nombre de pixels par tuile : 128 × 128 = 16 384.
pub const TILE_PIXELS: usize = TILE_SIZE * TILE_SIZE;

/// Bits par mot de couche bit (miroir de la taille d'un `long` Java).
pub const WORD_BITS: usize = 64;

/// Mots d'une couche `DataSize.BIT` : 16 384 bits / 64 = 256 mots.
pub const BIT_LAYER_WORDS: usize = TILE_PIXELS / WORD_BITS;

/// Cellules d'une couche `DataSize.BIT_PER_CHUNK` : 8 × 8 chunks de 16 × 16.
pub const BIT_PER_CHUNK_LAYER_CELLS: usize = (TILE_SIZE / 16) * (TILE_SIZE / 16);

/// Mots d'une couche `DataSize.BIT_PER_CHUNK` : 64 bits / 64 = 1 mot.
pub const BIT_PER_CHUNK_LAYER_WORDS: usize = BIT_PER_CHUNK_LAYER_CELLS / WORD_BITS;

/// Octets d'une couche `DataSize.NIBBLE` : 16 384 pixels / 2 = 8 192 octets.
pub const NIBBLE_LAYER_BYTES: usize = TILE_PIXELS / 2;

/// Version du contrat de layout `WpTileView` (incrémenter à TOUTE évolution).
pub const WP_TILE_VIEW_ABI_VERSION: u32 = 1;

/// Ordinaux de `org.pepsoft.worldpainter.layers.Layer.DataSize` — ne pas renuméroter.
pub mod data_size {
    /// `Layer.DataSize.BIT.ordinal()` : un bit par pixel.
    pub const BIT: u32 = 0;
    /// `Layer.DataSize.NIBBLE.ordinal()` : 4 bits par pixel, 2 pixels par octet.
    pub const NIBBLE: u32 = 1;
    /// `Layer.DataSize.BYTE.ordinal()` : un octet par pixel (intensité 0-255).
    pub const BYTE: u32 = 2;
    /// `Layer.DataSize.BIT_PER_CHUNK.ordinal()` : un bit par chunk 16×16.
    pub const BIT_PER_CHUNK: u32 = 3;
}

// ===== Indexation =====

/// Index intra-tuile du pixel `(x, y)`, identique à `Tile` :
/// `x | (y << TILE_SIZE_BITS)`.
#[inline]
pub const fn tile_index(x: usize, y: usize) -> usize {
    x | (y << TILE_SIZE_BITS)
}

/// Index du chunk `(x >> 4, y >> 4)` dans une couche `BIT_PER_CHUNK`,
/// identique à `Tile.getBitPerChunkLayerValue` :
/// `(x >> 4) + (y >> 4) * (TILE_SIZE >> 4)`.
#[inline]
pub const fn bit_per_chunk_index(x: usize, y: usize) -> usize {
    (x >> 4) + ((y >> 4) * (TILE_SIZE >> 4))
}

// ===== Validation des longueurs =====

/// Longueur de buffer brute valide pour le contrat : `0` (donnée absente) ou un
/// multiple **positif** de [`TILE_PIXELS`] — une tuile, ou plusieurs tuiles
/// pour un buffer plat de zone. C'est la règle vérifiée par l'export JNI
/// `nativeTileViewCheck` (miroir Java : `TileView.isValidLength`).
pub const fn is_valid_buffer_len(len: i32) -> bool {
    len >= 0 && (len as i64) % (TILE_PIXELS as i64) == 0
}

/// Vérifie les trois longueurs de buffers principales d'une vue (règle
/// « multiples de `TILE_SIZE * TILE_SIZE` ou 0 » du plan §0.3). Utilisé par
/// l'export JNI `nativeTileViewCheck` ; les fonctions Rust internes préfèrent
/// [`WpTileView::validate`], plus stricte.
pub const fn check_lengths(heights_len: i32, terrain_len: i32, water_len: i32) -> WeltError {
    if !is_valid_buffer_len(heights_len)
        || !is_valid_buffer_len(terrain_len)
        || !is_valid_buffer_len(water_len)
    {
        WeltError::IllegalArgument
    } else {
        WeltError::Ok
    }
}

// ===== Vues =====

/// Vue d'UNE couche bit d'une tuile.
///
/// `words` suit la layout de `java.util.BitSet` : le bit logique *n* est le bit
/// `(n % 64)` du mot `(n / 64)` (LSB d'abord). La facade Java convertit via
/// `BitSet.toLongArray()` **puis paddue au nombre exact de mots** (le tableau
/// d'un `BitSet` Java peut être plus court).
#[derive(Clone, Copy)]
#[repr(C)]
pub struct WpBitLayerView {
    /// Identifiant de couche opaque (cf. module doc : interprété par export).
    pub layer_id: u32,
    /// [`data_size::BIT`] ou [`data_size::BIT_PER_CHUNK`].
    pub data_size: u32,
    /// Mots 64 bits (boutisme natif), ou pointeur quelconque si `words_len == 0`.
    pub words: *const u64,
    /// Nombre de mots : exactement [`BIT_LAYER_WORDS`] (BIT) ou
    /// [`BIT_PER_CHUNK_LAYER_WORDS`] (BIT_PER_CHUNK).
    pub words_len: usize,
}

/// Vue d'UNE couche byte d'une tuile (`DataSize.BYTE` ou `DataSize.NIBBLE`).
#[derive(Clone, Copy)]
#[repr(C)]
pub struct WpByteLayerView {
    /// Identifiant de couche opaque (cf. module doc : interprété par export).
    pub layer_id: u32,
    /// [`data_size::BYTE`] ou [`data_size::NIBBLE`].
    pub data_size: u32,
    /// Données, ou pointeur quelconque si `data_len == 0`.
    pub data: *const u8,
    /// [`TILE_PIXELS`] (BYTE) ou [`NIBBLE_LAYER_BYTES`] (NIBBLE).
    pub data_len: usize,
}

/// Vue read-only d'une tuile 128×128, C-compatible (pointeurs + longueurs).
///
/// Une zone rectangulaire multi-tuiles = un tableau (`*const WpTileView` +
/// nombre). Les tuiles « tall » (`max_height - min_height > 256`, buffers `int[]`
/// / `short[]` Java) ne sont PAS représentables en v1 : la facade Java les
/// rejette ; une extension (buffers 32 bits dédiés + flag) passerait par
/// `WP_TILE_VIEW_ABI_VERSION + 1`.
///
/// Type valeur peu profond (pointeurs + longueurs, aucune ownership) :
/// [`Copy`] effectue une copie de bits sans dupliquer les buffers pointés.
#[derive(Clone, Copy)]
#[repr(C)]
pub struct WpTileView {
    /// Version du contrat de layout : [`WP_TILE_VIEW_ABI_VERSION`].
    pub abi_version: u32,
    /// Réservé — doit être 0 en v1.
    pub flags: u32,
    /// Coordonnées de la tuile dans la grille (`Tile.getX()`).
    pub x: i32,
    /// Coordonnées de la tuile dans la grille (`Tile.getY()`).
    pub y: i32,
    /// Hauteur minimale (`Tile.getMinHeight()`).
    pub min_height: i32,
    /// Hauteur maximale (`Tile.getMaxHeight()`).
    pub max_height: i32,
    /// Hauteurs brutes en point fixe /256, zéro-based — `TILE_PIXELS` entrées,
    /// ou absentes (longueur 0).
    pub heights: *const u16,
    /// Nombre d'entrées de `heights` (en éléments, pas en octets).
    pub heights_len: usize,
    /// Ordinaux `Terrain.values()` — `TILE_PIXELS` entrées, ou absent.
    pub terrain: *const u8,
    /// Nombre d'entrées de `terrain`.
    pub terrain_len: usize,
    /// Niveaux d'eau bruts, zéro-based — `TILE_PIXELS` entrées, ou absent.
    pub water: *const u8,
    /// Nombre d'entrées de `water`.
    pub water_len: usize,
    /// Couches bit de la tuile (`WpBitLayerView[n]`), ou absent si 0.
    pub bit_layers: *const WpBitLayerView,
    /// Nombre de vues dans `bit_layers`.
    pub bit_layers_len: usize,
    /// Couches byte de la tuile (`WpByteLayerView[n]`), ou absent si 0.
    pub byte_layers: *const WpByteLayerView,
    /// Nombre de vues dans `byte_layers`.
    pub byte_layers_len: usize,
}

impl WpBitLayerView {
    /// Vérifie les invariants de cette vue (data_size connu, nombre de mots
    /// exact, pointeur non nul si données présentes).
    ///
    /// # Safety
    /// La vue doit être en mémoire valide (construite par l'appelant ou la
    /// facade Java via un export JNI) ; cette méthode ne déréférence PAS les
    /// pointeurs de données, seulement la vue elle-même.
    pub unsafe fn validate(&self) -> WeltError {
        let expected = match self.data_size {
            data_size::BIT => BIT_LAYER_WORDS,
            data_size::BIT_PER_CHUNK => BIT_PER_CHUNK_LAYER_WORDS,
            _ => return WeltError::IllegalArgument,
        };
        if self.words_len != expected {
            return WeltError::IllegalArgument;
        }
        if (self.words_len > 0) && self.words.is_null() {
            return WeltError::NullPointer;
        }
        WeltError::Ok
    }
}

impl WpByteLayerView {
    /// Vérifie les invariants de cette vue (data_size connu, longueur exacte,
    /// pointeur non nul si données présentes).
    ///
    /// # Safety
    /// Cf. [`WpBitLayerView::validate`] : seule la vue elle-même est lue.
    pub unsafe fn validate(&self) -> WeltError {
        let expected = match self.data_size {
            data_size::BYTE => TILE_PIXELS,
            data_size::NIBBLE => NIBBLE_LAYER_BYTES,
            _ => return WeltError::IllegalArgument,
        };
        if self.data_len != expected {
            return WeltError::IllegalArgument;
        }
        if (self.data_len > 0) && self.data.is_null() {
            return WeltError::NullPointer;
        }
        WeltError::Ok
    }
}

impl WpTileView {
    /// Vérifie tous les invariants d'une vue complète : version, flags,
    /// longueurs exactes par tuile, cohérence pointeur/longueur, validité de
    /// chaque couche.
    ///
    /// Plus stricte que [`check_lengths`] (qui valide des longueurs brutes au
    /// sens « zone ») : ici chaque buffer est celui d'UNE tuile — 0 ou
    /// exactement [`TILE_PIXELS`] entrées.
    ///
    /// # Safety
    /// La vue et ses tableaux `bit_layers`/`byte_layers` doivent être en
    /// mémoire valide ; les pointeurs de données ne sont pas déréférencés
    /// (uniquement testés contre null).
    pub unsafe fn validate(&self) -> WeltError {
        if self.abi_version != WP_TILE_VIEW_ABI_VERSION {
            return WeltError::IllegalArgument;
        }
        if self.flags != 0 {
            return WeltError::IllegalArgument;
        }
        if self.max_height < self.min_height {
            return WeltError::IllegalArgument;
        }
        for (pointer, length) in [
            (self.heights.cast::<u8>(), self.heights_len),
            (self.terrain, self.terrain_len),
            (self.water, self.water_len),
        ] {
            if (length != 0) && (length != TILE_PIXELS) {
                return WeltError::IllegalArgument;
            }
            if (length > 0) && pointer.is_null() {
                return WeltError::NullPointer;
            }
        }
        if (self.bit_layers_len > 0) && self.bit_layers.is_null() {
            return WeltError::NullPointer;
        }
        // SAFETY: le contrat de l'appelant garantit que le tableau de vues est
        // valide sur `bit_layers_len` entrées ; `validate` ne lit que ces vues.
        for index in 0..self.bit_layers_len {
            let view = unsafe { self.bit_layers.add(index) };
            // SAFETY: `view` pointe dans le tableau valide ci-dessus.
            if unsafe { (*view).validate() } != WeltError::Ok {
                return WeltError::IllegalArgument;
            }
        }
        if (self.byte_layers_len > 0) && self.byte_layers.is_null() {
            return WeltError::NullPointer;
        }
        // SAFETY: cf. `bit_layers` ci-dessus.
        for index in 0..self.byte_layers_len {
            let view = unsafe { self.byte_layers.add(index) };
            // SAFETY: `view` pointe dans le tableau valide ci-dessus.
            if unsafe { (*view).validate() } != WeltError::Ok {
                return WeltError::IllegalArgument;
            }
        }
        WeltError::Ok
    }
}

// ===== Gel du layout v1 (contrat d'ABI) =====
// Toute modification du layout doit passer par une incrément de
// WP_TILE_VIEW_ABI_VERSION ; ces assertions détectent un glissement accidentel
// sur les cibles 64 bits (Windows/Linux/macOS x64 et ARM).

#[cfg(target_pointer_width = "64")]
const _: () = {
    use std::mem::{offset_of, size_of};

    assert!(size_of::<WpTileView>() == 104);
    assert!(std::mem::align_of::<WpTileView>() == 8);
    assert!(offset_of!(WpTileView, heights) == 24);
    assert!(offset_of!(WpTileView, heights_len) == 32);
    assert!(offset_of!(WpTileView, terrain) == 40);
    assert!(offset_of!(WpTileView, water) == 56);
    assert!(offset_of!(WpTileView, bit_layers) == 72);
    assert!(offset_of!(WpTileView, byte_layers) == 88);
    assert!(offset_of!(WpTileView, byte_layers_len) == 96);

    assert!(size_of::<WpBitLayerView>() == 24);
    assert!(offset_of!(WpBitLayerView, words) == 8);

    assert!(size_of::<WpByteLayerView>() == 24);
    assert!(offset_of!(WpByteLayerView, data) == 8);
};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn constants_mirror_java() {
        assert_eq!(TILE_SIZE, 128);
        assert_eq!(TILE_SIZE_BITS, 7);
        assert_eq!(TILE_SIZE_MASK, 127);
        assert_eq!(TILE_PIXELS, 16384);
        assert_eq!(BIT_LAYER_WORDS, 256);
        assert_eq!(BIT_PER_CHUNK_LAYER_CELLS, 64);
        assert_eq!(BIT_PER_CHUNK_LAYER_WORDS, 1);
        assert_eq!(NIBBLE_LAYER_BYTES, 8192);
    }

    #[test]
    fn indexes_match_tile_semantics() {
        // Référence : Tile.getBitPerBlockLayerValue / Tile.setBitLayerValue.
        assert_eq!(tile_index(0, 0), 0);
        assert_eq!(tile_index(127, 0), 127);
        assert_eq!(tile_index(0, 1), 128);
        assert_eq!(tile_index(127, 127), 127 | (127 << 7));
        // Référence : Tile.getBitPerChunkLayerValue.
        assert_eq!(bit_per_chunk_index(0, 0), 0);
        assert_eq!(bit_per_chunk_index(16, 0), 1);
        assert_eq!(bit_per_chunk_index(0, 16), 8);
        assert_eq!(bit_per_chunk_index(127, 127), 7 + 7 * 8);
    }

    #[test]
    fn buffer_len_rules() {
        // Règle « multiples de TILE_SIZE*TILE_SIZE ou 0 » (nativeTileViewCheck).
        for len in [0, 16384, 32768, 16384 * 17] {
            assert!(is_valid_buffer_len(len), "len {len} devrait être valide");
        }
        for len in [-1, -16384, 1, 16383, 16385] {
            assert!(!is_valid_buffer_len(len), "len {len} devrait être invalide");
        }
        assert_eq!(check_lengths(16384, 16384, 16384), WeltError::Ok);
        assert_eq!(check_lengths(0, 0, 0), WeltError::Ok);
        assert_eq!(check_lengths(32768, 16384, 0), WeltError::Ok);
        assert_eq!(
            check_lengths(16384, 16385, 16384),
            WeltError::IllegalArgument
        );
        assert_eq!(check_lengths(-16384, 0, 0), WeltError::IllegalArgument);
    }

    #[test]
    fn validates_assembled_views() {
        let heights = [0u16; TILE_PIXELS];
        let terrain = [0u8; TILE_PIXELS];
        let water = [0u8; TILE_PIXELS];
        let view = WpTileView {
            abi_version: WP_TILE_VIEW_ABI_VERSION,
            flags: 0,
            x: 3,
            y: -4,
            min_height: 0,
            max_height: 128,
            heights: heights.as_ptr(),
            heights_len: heights.len(),
            terrain: terrain.as_ptr(),
            terrain_len: terrain.len(),
            water: water.as_ptr(),
            water_len: water.len(),
            bit_layers: std::ptr::null(),
            bit_layers_len: 0,
            byte_layers: std::ptr::null(),
            byte_layers_len: 0,
        };
        // SAFETY: vue construite sur des tableaux locaux valides.
        assert_eq!(unsafe { view.validate() }, WeltError::Ok);

        // Version inconnue → IllegalArgument.
        let mut bad = view;
        bad.abi_version = 99;
        // SAFETY: cf. ci-dessus.
        assert_eq!(unsafe { bad.validate() }, WeltError::IllegalArgument);

        // Longueur ni 0 ni TILE_PIXELS → IllegalArgument.
        let mut bad = view;
        bad.terrain_len = 32768;
        // SAFETY: cf. ci-dessus.
        assert_eq!(unsafe { bad.validate() }, WeltError::IllegalArgument);

        // Données annoncées mais pointeur nul → NullPointer.
        let mut bad = view;
        bad.water = std::ptr::null();
        // SAFETY: cf. ci-dessus.
        assert_eq!(unsafe { bad.validate() }, WeltError::NullPointer);

        // Absent (longueur 0, pointeur nul) → Ok.
        let mut partial = view;
        partial.heights = std::ptr::null();
        partial.heights_len = 0;
        partial.water = std::ptr::null();
        partial.water_len = 0;
        // SAFETY: cf. ci-dessus.
        assert_eq!(unsafe { partial.validate() }, WeltError::Ok);
    }

    #[test]
    fn validates_layer_views() {
        let words = [0u64; BIT_LAYER_WORDS];
        let bit = WpBitLayerView {
            layer_id: 7,
            data_size: data_size::BIT,
            words: words.as_ptr(),
            words_len: words.len(),
        };
        // SAFETY: vue sur un tableau local valide.
        assert_eq!(unsafe { bit.validate() }, WeltError::Ok);

        let chunk = WpBitLayerView {
            layer_id: 8,
            data_size: data_size::BIT_PER_CHUNK,
            words: words.as_ptr(),
            words_len: BIT_PER_CHUNK_LAYER_WORDS,
        };
        // SAFETY: cf. ci-dessus.
        assert_eq!(unsafe { chunk.validate() }, WeltError::Ok);

        let mut bad = bit;
        bad.words_len = 255; // pas le nombre exact de mots
                             // SAFETY: cf. ci-dessus.
        assert_eq!(unsafe { bad.validate() }, WeltError::IllegalArgument);

        let mut bad = bit;
        bad.words = std::ptr::null();
        // SAFETY: cf. ci-dessus.
        assert_eq!(unsafe { bad.validate() }, WeltError::NullPointer);

        let data = [0u8; NIBBLE_LAYER_BYTES];
        let nibble = WpByteLayerView {
            layer_id: 9,
            data_size: data_size::NIBBLE,
            data: data.as_ptr(),
            data_len: data.len(),
        };
        // SAFETY: vue sur un tableau local valide.
        assert_eq!(unsafe { nibble.validate() }, WeltError::Ok);

        let byte_data = [0u8; TILE_PIXELS];
        let byte = WpByteLayerView {
            layer_id: 10,
            data_size: data_size::BYTE,
            data: byte_data.as_ptr(),
            data_len: byte_data.len(),
        };
        // SAFETY: vue sur un tableau local valide.
        assert_eq!(unsafe { byte.validate() }, WeltError::Ok);

        // Une vue de couche ne peut PAS être « absente » : une couche sans
        // données n'est tout simplement pas dans le tableau de vues. Longueur
        // invalide → IllegalArgument (et pas NullPointer, la donnée est nulle
        // mais la longueur fautive est testée d'abord).
        let absent = WpByteLayerView {
            layer_id: 11,
            data_size: data_size::BYTE,
            data: std::ptr::null(),
            data_len: 0,
        };
        // SAFETY: vue locale, aucun pointeur déréférencé.
        assert_eq!(unsafe { absent.validate() }, WeltError::IllegalArgument);
    }
}
