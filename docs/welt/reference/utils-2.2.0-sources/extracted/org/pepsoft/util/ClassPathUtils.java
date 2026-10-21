package org.pepsoft.util;

import org.pepsoft.util.mdc.MDCCapturingRuntimeException;

import java.io.IOException;
import java.nio.charset.Charset;

public final class ClassPathUtils {
    private ClassPathUtils() {
        // Prevent instantiation
    }

    /**
     * Load a file resource from the system classpath into a string.
     */
    public static String loadStringFromClasspath(String path, Charset charset) throws MDCCapturingRuntimeException {
        try {
            return FileUtils.load(ClassLoader.getSystemResourceAsStream(path), charset);
        } catch (IOException e) {
            throw new MDCCapturingRuntimeException(e.getClass().getSimpleName() + " while loading " + path + " from classpath", e);
        }
    }

    private static final StackWalker STACK_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
}
