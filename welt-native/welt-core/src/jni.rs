//! Exports JNI du pont Welt (G1, Phase 0 — charte §5, plan §0.5).
//!
//! # JNI « manuel », sans crate
//!
//! Aucune dépendance (charte §5 : pas de crate hors workspace) : la table des
//! fonctions JNI (`struct JNINativeInterface_` du `jni.h` de la JVM) est
//! déclarée en Rust avec les **indices exacts** du standard — seuls les
//! exemplaires utilisés sont typés en pointeurs de fonction, les autres en
//! `*mut c_void` (tous les pointeurs ont la même taille : seuls le compte et
//! l'ordre des champs comptent). Pour ajouter une fonction JNI : insérer le
//! champ au bon index avec sa signature, ne jamais réordonner.
//!
//! La JVM passe à chaque export un `JNIEnv *` : un pointeur vers le `JNIEnv`
//! du thread, qui contient lui-même le pointeur vers la table. D'où le double
//! déréférencement `(*(*env).functions).ThrowNew(...)` — équivalent exact du C
//! `(*env)->ThrowNew(env, ...)`.
//!
//! # Convention d'export (charte §5)
//!
//! - Symboles : `Java_org_pepsoft_worldpainter_nativeapi_WpNative_<method>`.
//! - Tout export encapsule son corps dans [`jni_catch`] (le « helper central »)
//!   : sur panic Rust → `java.lang.RuntimeException` levée via `ThrowNew`
//!   (message fourni par [`crate::error::panic_message`]) et retour `-1`.
//! - `extern "system"` : convention d'appel JNI (`win64` sur Windows, C ailleurs).
//!
//! Ce module n'utilise QUE `error` et `abi` (modules G1) — jamais `noise` ni
//! `rng` (G2/G3).

// Les noms de types JNI (jint, jclass, ...) reprennent ceux du standard :
// style imposé par l'ABI, pas par rustc.
#![allow(non_camel_case_types)]

use crate::abi;
use crate::error::{self, JNI_ERROR_RETURN, RUNTIME_EXCEPTION_CLASS};
use std::ffi::{c_char, c_void, CStr, CString};
use std::panic::{catch_unwind, AssertUnwindSafe};

// ===== Types JNI bruts (subset utilisé) =====

pub type jint = i32;
pub type jsize = i32;
pub type jlong = i64;
pub type jobject = *mut c_void;
pub type jclass = *mut c_void;
pub type jthrowable = *mut c_void;

/// Miroir Rust du `JNIEnv` C/C++ : le pointeur vers la table des fonctions JNI.
#[repr(C)]
pub struct JNIEnv {
    /// Toujours non-null dans un export appelé par une JVM.
    pub functions: *const JNINativeInterface,
}

/// Préfixe de la table des fonctions JNI (`struct JNINativeInterface_`).
///
/// ⚠️ Layout ABI : les indices sont ceux du standard JNI — les 4 premiers
/// champs sont réservés, `get_version` est l'indice 4, `find_class` l'indice 6,
/// `throw_new` l'indice 14. Ne jamais réordonner ni insérer. (Les NOMS des
/// champs ne font pas partie de l'ABI — seuls l'ordre et les types comptent —
/// ils suivent donc la convention rustfmt.)
#[repr(C)]
pub struct JNINativeInterface {
    /// Indice 0 : réservé.
    reserved0: *mut c_void,
    /// Indice 1 : réservé.
    reserved1: *mut c_void,
    /// Indice 2 : réservé.
    reserved2: *mut c_void,
    /// Indice 3 : réservé.
    reserved3: *mut c_void,
    /// Indice 4 : `jint GetVersion(JNIEnv *)`.
    get_version: *mut c_void,
    /// Indice 5 : `jclass DefineClass(JNIEnv *, const char *, jobject, const jbyte *, jsize)`.
    define_class: *mut c_void,
    /// Indice 6 : `jclass FindClass(JNIEnv *, const char *)`.
    find_class: unsafe extern "system" fn(env: *mut JNIEnv, name: *const c_char) -> jclass,
    /// Indice 7.
    from_reflected_method: *mut c_void,
    /// Indice 8.
    from_reflected_field: *mut c_void,
    /// Indice 9.
    to_reflected_method: *mut c_void,
    /// Indice 10.
    get_superclass: *mut c_void,
    /// Indice 11.
    is_assignable_from: *mut c_void,
    /// Indice 12.
    to_reflected_field: *mut c_void,
    /// Indice 13 : `jint Throw(JNIEnv *, jthrowable)`.
    throw: *mut c_void,
    /// Indice 14 : `jint ThrowNew(JNIEnv *, jclass, const char *)`.
    throw_new:
        unsafe extern "system" fn(env: *mut JNIEnv, clazz: jclass, message: *const c_char) -> jint,
}

// ===== Helpers =====

/// Lève l'exception `class_name` (nom JNI « slashé », ex. `java/lang/RuntimeException`)
/// avec `message` — `(*env).throw_new(env, class, message)` via la table brute.
///
/// # Safety
/// - `env` doit être le `JNIEnv` valide du thread Java courant (un export JNI,
///   ou un thread attaché via `AttachCurrentThread`).
/// - Ne doit être appelée que pendant un export JNI (frame locale valide :
///   la classe retournée par `FindClass` est une référence locale).
/// - Si `FindClass` échoue (classe introuvable), une exception est déjà en
///   attente côté JVM : l'appelant doit retourner [`JNI_ERROR_RETURN`] sans
///   autre appel JNI bloquant.
pub unsafe fn throw_new(env: *mut JNIEnv, class_name: &CStr, message: &str) -> jint {
    // SAFETY: préconditions documentées ci-dessus (env valide).
    let functions = unsafe { &*(*env).functions };
    let message_bytes = error::message_bytes_for_jni(message);
    let message_c = CString::new(message_bytes).unwrap_or_else(|_| c"Rust panic".into());
    // SAFETY: idem ; `class_name` vient d'un littéral C du crate.
    let clazz = (functions.find_class)(env, class_name.as_ptr());
    if clazz.is_null() {
        return JNI_ERROR_RETURN;
    }
    // SAFETY: idem ; `clazz` est une référence locale valide de ce frame.
    (functions.throw_new)(env, clazz, message_c.as_ptr())
}

/// **Helper central** (charte §5) : exécute `body` ; sur panic Rust, lève
/// `java.lang.RuntimeException` côté JVM avec le message de la panic et
/// retourne `-1`. Sans panic, retourne simplement le résultat de `body`
/// (lui-même un code [`WeltError`] ou une valeur documentée).
///
/// Tout nouveau export JNI DOIT passer par ce helper :
///
/// ```ignore
/// #[no_mangle]
/// pub extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_WpNative_exemple(
///     env: *mut JNIEnv,
///     _class: jclass,
/// ) -> jint {
///     unsafe { jni_catch(env, || { /* corps, sans env dans le chemin heureux */ }) }
/// }
/// ```
///
/// # Safety
/// `env` must be a valid JNI environment pointer from the current JVM thread.
/// If `body` panics, this function uses that pointer to raise a Java exception.
///
/// [`AssertUnwindSafe`] est assumé : les corps d'export ne capturent que des
/// pointeurs bruts/paramètres par valeur, jamais d'état exigeant UnwindSafe.
pub unsafe fn jni_catch<F>(env: *mut JNIEnv, body: F) -> jint
where
    F: FnOnce() -> jint,
{
    match catch_unwind(AssertUnwindSafe(body)) {
        Ok(result) => result,
        Err(panic_payload) => {
            let message = error::panic_message(panic_payload);
            // SAFETY: `env` est le JNIEnv fourni par la JVM à l'export courant ;
            // nous sommes dans son frame, `ThrowNew` y est autorisé.
            unsafe { throw_new(env, RUNTIME_EXCEPTION_CLASS, &message) };
            JNI_ERROR_RETURN
        }
    }
}

// ===== Exports (Java: org.pepsoft.worldpainter.nativeapi.WpNative) =====

/// Version du pont natif Welt. Doit rester en phase avec
/// `WpNative.EXPECTED_NATIVE_VERSION` (miroir Java).
pub const NATIVE_VERSION: jint = 1;

/// `WpNative.nativeVersion()` : version du pont natif (1 en Phase 0).
///
/// Retour : version ≥ 1 ; `-1` + `java.lang.RuntimeException` sur panic interne.
///
/// # Safety
/// `env` must be the valid JNI environment pointer supplied by the JVM.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_WpNative_nativeVersion(
    env: *mut JNIEnv,
    _class: jclass,
) -> jint {
    // SAFETY: `env` is supplied by the JVM for this native call.
    unsafe { jni_catch(env, || NATIVE_VERSION) }
}

/// `WpNative.nativeTileViewCheck(int heightsLen, int terrainLen, int waterLen)` :
/// validation des invariants de tailles du contrat `WpTileView` (plan §0.3) —
/// chaque longueur doit être `0` (donnée absente) ou un multiple positif de
/// `TILE_SIZE * TILE_SIZE` (16 384) ; les longueurs négatives sont rejetées.
///
/// Retour : [`WeltError::Ok`] (0) si les invariants tiennent, sinon
/// [`WeltError::IllegalArgument`] (2) — codes sans exception, l'appelant Java
/// décide ; `-1` + `java.lang.RuntimeException` sur panic interne.
///
/// # Safety
/// `env` must be the valid JNI environment pointer supplied by the JVM.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_WpNative_nativeTileViewCheck(
    env: *mut JNIEnv,
    _class: jclass,
    heights_len: jint,
    terrain_len: jint,
    water_len: jint,
) -> jint {
    // SAFETY: `env` is supplied by the JVM for this native call.
    unsafe {
        jni_catch(env, || {
            // Corps pur (pas d'accès mémoire, pas d'env) : la validation est
            // déléguée au module ABI, seul propriétaire des invariants.
            abi::check_lengths(heights_len, terrain_len, water_len).code()
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Le préfixe de la table doit avoir exactement 15 entrées (indices 0..=14),
    /// `throw_new` à l'indice 14 : layout figé par le standard JNI.
    #[test]
    fn jni_vtable_layout_is_pinned() {
        use std::mem::{offset_of, size_of};
        assert_eq!(size_of::<JNINativeInterface>(), 15 * 8);
        assert_eq!(size_of::<JNIEnv>(), 8);
        assert_eq!(offset_of!(JNINativeInterface, get_version), 4 * 8);
        assert_eq!(offset_of!(JNINativeInterface, find_class), 6 * 8);
        assert_eq!(offset_of!(JNINativeInterface, throw), 13 * 8);
        assert_eq!(offset_of!(JNINativeInterface, throw_new), 14 * 8);
    }

    /// Les exports sont appelables sans JVM sur leur chemin pur : le `JNIEnv`
    /// n'est déréférencé QUE dans le chemin panic (jamais ici).
    #[test]
    fn exports_run_pure_bodies_without_env() {
        let null_env = std::ptr::null_mut();
        let null_class = std::ptr::null_mut();
        assert_eq!(
            unsafe {
                Java_org_pepsoft_worldpainter_nativeapi_WpNative_nativeVersion(null_env, null_class)
            },
            1
        );
        assert_eq!(
            unsafe {
                Java_org_pepsoft_worldpainter_nativeapi_WpNative_nativeTileViewCheck(
                    null_env, null_class, 16384, 16384, 16384,
                )
            },
            0
        );
        assert_eq!(
            unsafe {
                Java_org_pepsoft_worldpainter_nativeapi_WpNative_nativeTileViewCheck(
                    null_env, null_class, 16384, 16385, 0,
                )
            },
            2
        );
    }

    /// Le helper central propage le résultat du corps sans toucher `env`.
    #[test]
    fn jni_catch_returns_body_result_on_happy_path() {
        // SAFETY: the closure cannot panic, so the helper does not dereference env.
        assert_eq!(unsafe { jni_catch(std::ptr::null_mut(), || 7) }, 7);
    }

    #[test]
    fn native_version_matches_java_constant() {
        // Miroir de WpNative.EXPECTED_NATIVE_VERSION (côté Java) — à garder en phase.
        assert_eq!(NATIVE_VERSION, 1);
        assert_eq!(JNI_ERROR_RETURN, -1);
    }
}
