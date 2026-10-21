/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.pepsoft.util;

/**
 * Utility methods for working with {@code int 0xrrggbb} encoded colour values.
 *
 * @author pepijn
 */
public final class ColourUtils {
    private ColourUtils() {
        // Prevent instantiation
    }

    /**
     * Mix two colours by mixing their red, green and blue components exactly equally. This is a convenience method for
     * calling {@code mix(colour1, colour2, 128)}.
     */
    public static int mix(int colour1, int colour2) {
        final int red   = ((colour1 & 0xFF0000) >> 17) + ((colour2 & 0xFF0000) >> 17);
        final int green = ((colour1 & 0x00FF00) >>  9) + ((colour2 & 0x00FF00) >>  9);
        final int blue  = ((colour1 & 0x0000FF) >>  1) + ((colour2 & 0x0000FF) >>  1);
        return (red << 16) | (green << 8) | blue;
    }

    /**
     * Mix two colours by mixing their red, green and blue components according to an alpha value between 0 and 256
     * (inclusive), where an alpha of 0 results in just {@code colour1} and 256 in just {@code colour2}.
     */
    public static int mix(int colour1, int colour2, int alpha) {
        final int red1   = (colour1 & 0xFF0000) >> 16;
        final int green1 = (colour1 & 0x00FF00) >> 8;
        final int blue1  =  colour1 & 0x0000FF;
        final int red2   = (colour2 & 0xFF0000) >> 16;
        final int green2 = (colour2 & 0x00FF00) >> 8;
        final int blue2  =  colour2 & 0x0000FF;
        final int red   = (red1   * alpha + red2   * (256 - alpha)) / 256;
        final int green = (green1 * alpha + green2 * (256 - alpha)) / 256;
        final int blue  = (blue1  * alpha + blue2  * (256 - alpha)) / 256;
        return (red << 16) | (green << 8) | blue;
    }
    
    /**
     * Multiplies each of component of {@code colour} with
     * {@code amount} and divides the result by 256.
     * 
     * @param colour The RGB colour to multiply.
     * @param amount The amount to multiply each colour band with, where 256
     *    leaves the colour unchanged.
     * @return The multiplied colour.
     */
    public static int multiply(int colour, int amount) {
        if (amount == 256) {
            return colour;
        }
        int red   = (colour & 0xFF0000) >> 16;
        int green = (colour & 0x00FF00) >> 8;
        int blue  =  colour & 0x0000FF;
        red = red * amount;
        if (red > 65535) {
            red = 65535;
        }
        green = green * amount;
        if (green > 65535) {
            green = 65535;
        }
        blue = blue * amount;
        if (blue > 65535) {
            blue = 65535;
        }
        return ((red << 8) & 0xFF0000) | (green & 0x00FF00) | ((blue >> 8) & 0x0000FF);
    }

    public static boolean isGreyScale(int colour) {
        final int red   = (colour & 0xFF0000) >> 16;
        final int green = (colour & 0x00FF00) >> 8;
        final int blue  =  colour & 0x0000FF;
        return (red == green) && (red == blue);
    }
}