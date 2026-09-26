//! Modèle d'erreurs du cœur natif Welt (G1, Phase 0 — charte §5, plan §0.5).
//!
//! Convention de mapping Rust → Java (charte §5) :
//!
//! - Tout export JNI (`jni.rs`) encapsule son corps dans [`std::panic::catch_unwind`]
//!   via le helper central `jni_catch`.
//! - Sur panic : le message est extrait par [`panic_message`], encodé pour JNI par
//!   [`message_bytes_for_jni`], puis `jni.rs` lève l'exception correspondante
//!   (`java.lang.RuntimeException` pour une panic) avec `ThrowNew` et l'export
//!   retourne [`JNI_ERROR_RETURN`] (`-1`).
//! - Sans panic, un export qui échoue retourne un code [`WeltError`] (sans exception) :
//!   c'est la monnaie d'échange « normale » Rust → Java pour les invariants
//!   documentés (l'appelant Java décide d'ignorer ou de lever).
//!
//! Ce module est volontairement `std`-pur et ne connaît pas les types JNI (qui
//! vivent dans `jni.rs` avec la table de fonctions brute) : il porte la partie
//! « mapping » (payload de panic → message, [`WeltError`] → code / classe
//! d'exception), `jni.rs` porte la mécanique d'appel.

use std::any::Any;
use std::ffi::CStr;

/// Valeur retournée par un export JNI après qu'une exception a été levée vers la
/// JVM (charte §5 : panic Rust → `java.lang.RuntimeException`, retour `-1`).
pub const JNI_ERROR_RETURN: i32 = -1;

/// Exception levée vers la JVM sur panic Rust (nom « slashé » attendu par
/// `FindClass`/`ThrowNew`).
pub const RUNTIME_EXCEPTION_CLASS: &CStr = c"java/lang/RuntimeException";

/// Codes d'erreur normalisés du pont Welt. Les discriminants font partie de
/// l'ABI (charte §5) : ne jamais les renuméroter — miroir Java dans
/// `WpNative.WELT_ERROR_*`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(i32)]
pub enum WeltError {
    /// Aucune erreur.
    Ok = 0,
    /// Un pointeur/référence obligatoire était null côté Java.
    NullPointer = 1,
    /// Un argument viole un invariant documenté (taille, plage, état).
    IllegalArgument = 2,
    /// Erreur interne inattendue (invariant cassé, état impossible).
    Internal = 3,
}

impl WeltError {
    /// Code numérique brut (`jint`) tel que retourné à la JVM.
    pub const fn code(self) -> i32 {
        self as i32
    }

    /// Nom court, pour les logs et messages.
    pub const fn name(self) -> &'static str {
        match self {
            WeltError::Ok => "Ok",
            WeltError::NullPointer => "NullPointer",
            WeltError::IllegalArgument => "IllegalArgument",
            WeltError::Internal => "Internal",
        }
    }

    /// Classe d'exception Java (« slashée ») correspondant à ce code, pour le
    /// helper `throw_new` de `jni.rs`.
    pub const fn exception_class(self) -> &'static CStr {
        match self {
            WeltError::Ok | WeltError::Internal => RUNTIME_EXCEPTION_CLASS,
            WeltError::NullPointer => c"java/lang/NullPointerException",
            WeltError::IllegalArgument => c"java/lang/IllegalArgumentException",
        }
    }

    /// Décode un code brut retourné par un export, si connu.
    pub const fn from_code(code: i32) -> Option<WeltError> {
        match code {
            0 => Some(WeltError::Ok),
            1 => Some(WeltError::NullPointer),
            2 => Some(WeltError::IllegalArgument),
            3 => Some(WeltError::Internal),
            _ => None,
        }
    }
}

/// Extrait un message lisible d'un payload de panic capturé par
/// [`std::panic::catch_unwind`] (charte §5 : panic → exception Java).
pub fn panic_message(payload: Box<dyn Any + Send>) -> String {
    if let Some(message) = payload.downcast_ref::<&'static str>() {
        (*message).to_string()
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message.clone()
    } else {
        "Rust panic (non-textual payload)".to_string()
    }
}

/// Encode un message en octets acceptables pour `ThrowNew`/`NewStringUTF`
/// (« modified UTF-8 » de la JVM) :
///
/// - aucun octet NUL intérieur (les chaînes C sont terminées par NUL et U+0000
///   se coderait `0xC0 0x80` en modified UTF-8) — remplacé par un espace ;
/// - les caractères hors BMP sont remplacés par U+FFFD : le modified UTF-8
///   exigerait des paires de substituts (CESU-8), inutile de risquer un message
///   corrompu pour un texte d'exception.
pub fn message_bytes_for_jni(message: &str) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(message.len());
    for character in message.chars() {
        match character {
            '\0' => bytes.push(b' '),
            character if (character as u32) > 0xFFFF => bytes.extend_from_slice("\u{FFFD}".as_bytes()),
            character => {
                let mut buffer = [0u8; 4];
                bytes.extend_from_slice(character.encode_utf8(&mut buffer).as_bytes());
            }
        }
    }
    bytes
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_are_abi_stable() {
        assert_eq!(WeltError::Ok.code(), 0);
        assert_eq!(WeltError::NullPointer.code(), 1);
        assert_eq!(WeltError::IllegalArgument.code(), 2);
        assert_eq!(WeltError::Internal.code(), 3);
    }

    #[test]
    fn from_code_roundtrips() {
        for code in [WeltError::Ok, WeltError::NullPointer, WeltError::IllegalArgument, WeltError::Internal] {
            assert_eq!(WeltError::from_code(code.code()), Some(code));
        }
        assert_eq!(WeltError::from_code(-1), None);
        assert_eq!(WeltError::from_code(4), None);
    }

    #[test]
    fn exception_classes_are_slashed_jni_names() {
        assert_eq!(WeltError::Internal.exception_class().to_bytes(), b"java/lang/RuntimeException");
        assert_eq!(WeltError::NullPointer.exception_class().to_bytes(), b"java/lang/NullPointerException");
        assert_eq!(
            WeltError::IllegalArgument.exception_class().to_bytes(),
            b"java/lang/IllegalArgumentException"
        );
    }

    #[test]
    fn panic_message_extracts_common_payloads() {
        assert_eq!(panic_message(Box::new("boom")), "boom");
        assert_eq!(panic_message(Box::new(String::from("boom 2"))), "boom 2");
        // Payload non textuel (ex. struct quelconque) : message générique, pas de crash.
        assert_eq!(panic_message(Box::new(42)), "Rust panic (non-textual payload)");
    }

    #[test]
    fn message_bytes_are_jni_safe() {
        // NUL intérieur éliminé.
        let bytes = message_bytes_for_jni("a\0b");
        assert_eq!(bytes, b"a b");
        // Hors BMP (emoji) → U+FFFD, pas de séquence 4 octets interdite en CESU-8.
        let bytes = message_bytes_for_jni("\u{1F600}");
        assert_eq!(bytes, "\u{FFFD}".as_bytes());
        // BMP accentué : conservé tel quel.
        let bytes = message_bytes_for_jni("taille invalide");
        assert_eq!(bytes, b"taille invalide");
    }
}
