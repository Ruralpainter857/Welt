package org.pepsoft.worldpainter.nativeapi;

/** Checks the production NativeLoader and WpNative classes against a built DLL. */
public final class NativeLoaderSmokeTest {
    private NativeLoaderSmokeTest() {
    }

    public static void main(String[] args) {
        if (!NativeLoader.isNativeAvailable()) {
            throw new IllegalStateException("NativeLoader did not load welt_core");
        }
        if (WpNative.nativeVersion() != WpNative.EXPECTED_NATIVE_VERSION) {
            throw new IllegalStateException("native version mismatch");
        }
        if (WpNative.nativeTileViewCheck(16_384, 16_384, 16_384) != WpNative.WELT_ERROR_OK) {
            throw new IllegalStateException("JNI ABI validation mismatch");
        }
        System.out.println("PRODUCTION NATIVE LOADER SMOKE TEST OK");
    }
}
