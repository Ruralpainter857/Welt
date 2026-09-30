/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.pepsoft.worldpainter.operations;

import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.WorldPainter;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import java.util.Random;

/**
 *
 * @author pepijn
 */
public class Erode extends AbstractBrushOperation {
    public Erode(WorldPainter view) {
        super("Erode", "Erode the terrain", view, 100, "operation.erode");
    }

    @Override
    protected void tick(int centreX, int centreY, boolean inverse, boolean first, float dynamicLevel) {
        final Dimension dimension = getDimension();
        if (dimension == null) {
            // Probably some kind of race condition
            return;
        }
        dimension.setEventsInhibited(true);
        try {
            int radius = getEffectiveRadius();
            if (!erodeNative(dimension, centreX, centreY, radius)) {
                erodeJava(dimension, centreX, centreY, radius);
            }
        } finally {
            dimension.setEventsInhibited(false);
        }
    }

    private boolean erodeNative(Dimension dimension, int centreX, int centreY, int radius) {
        // Filters can read current terrain heights, so their decisions must stay interleaved with edits.
        if (getFilter() != null || !Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) {
            return false;
        }
        long diameterLong = 2L * radius + 1L;
        if (radius < 0 || diameterLong > 512L) {
            return false;
        }
        long windowWidthLong = diameterLong + 2L;
        long operationArea = diameterLong * diameterLong;
        long windowArea = windowWidthLong * windowWidthLong;
        long controlLength = operationArea * 3L;
        long writeLogLength = operationArea * 4L;
        if (windowArea > MAX_NATIVE_CELLS
                || controlLength > MAX_NATIVE_CELLS || writeLogLength > MAX_NATIVE_CELLS) {
            return false;
        }
        int diameter = (int) diameterLong;
        int windowWidth = (int) windowWidthLong;
        ensureNativeBuffers(radius, (int) windowArea, (int) controlLength, (int) writeLogLength);

        for (int x = 0; x < diameter; x++) {
            for (int y = 0; y < diameter; y++) {
                int controlIndex = (x * diameter + y) * 3;
                int worldX = centreX + x - radius;
                int worldY = centreY + y - radius;
                float strength = getStrength(centreX, centreY, worldX, worldY);
                if ((strength == 1.0f) || (random.nextFloat() < strength)) {
                    nativeControls[controlIndex] = 1;
                    nativeControls[controlIndex + 1] = (byte) (random.nextBoolean() ? 1 : 0);
                    nativeControls[controlIndex + 2] = (byte) (random.nextBoolean() ? 1 : 0);
                } else {
                    nativeControls[controlIndex] = 0;
                    nativeControls[controlIndex + 1] = 0;
                    nativeControls[controlIndex + 2] = 0;
                }
            }
        }

        for (int x = 0; x < windowWidth; x++) {
            for (int y = 0; y < windowWidth; y++) {
                nativeHeights[x * windowWidth + y] = dimension.getRawHeightAt(
                        centreX + x - radius - 1, centreY + y - radius - 1);
            }
        }

        nativeWriteCount[0] = 0;
        if (!NativeSlices.erodeRawHeightRegion(radius, nativeHeights,
                nativeControls, nativeWriteLog, nativeWriteCount)) {
            erodeHeightBufferInJava(radius, diameter, windowWidth,
                    nativeHeights, nativeControls, nativeWriteLog, nativeWriteCount);
        }

        for (int index = 0; index < nativeWriteCount[0]; index += 2) {
            int cellIndex = nativeWriteLog[index];
            dimension.setRawHeightAt(centreX + cellIndex / windowWidth - radius - 1,
                    centreY + cellIndex % windowWidth - radius - 1,
                    nativeWriteLog[index + 1]);
        }
        return true;
    }

    private void ensureNativeBuffers(int radius, int heightLength,
                                     int controlLength, int writeLogLength) {
        if (nativeRadius != radius) {
            nativeRadius = radius;
            nativeHeights = new int[heightLength];
            nativeControls = new byte[controlLength];
            nativeWriteLog = new int[writeLogLength];
        }
    }

    private static void erodeHeightBufferInJava(int radius, int diameter, int windowWidth,
                                                int[] heights, byte[] controls,
                                                int[] writeLog, int[] writeCount) {
        int writeIndex = 0;
        for (int x = 0; x < diameter; x++) {
            for (int y = 0; y < diameter; y++) {
                int controlIndex = (x * diameter + y) * 3;
                if (controls[controlIndex] == 0) {
                    continue;
                }
                boolean reverseX = controls[controlIndex + 1] != 0;
                boolean reverseY = controls[controlIndex + 2] != 0;
                int lowestDx = 0;
                int lowestDy = 0;
                int lowestRawHeight = Integer.MAX_VALUE;
                for (int xOrder = 0; xOrder < 3; xOrder++) {
                    int dx = reverseX ? 2 - xOrder : xOrder;
                    for (int yOrder = 0; yOrder < 3; yOrder++) {
                        int dy = reverseY ? 2 - yOrder : yOrder;
                        int value = heights[(x + dx) * windowWidth + y + dy];
                        if (value < lowestRawHeight) {
                            lowestRawHeight = value;
                            lowestDx = dx;
                            lowestDy = dy;
                        }
                    }
                }
                if ((lowestDx == 1) && (lowestDy == 1)) {
                    continue;
                }
                int centreIndex = (x + 1) * windowWidth + y + 1;
                int lowestIndex = (x + lowestDx) * windowWidth + y + lowestDy;
                int difference = heights[centreIndex] - heights[lowestIndex];
                int amount = Math.min((int) (difference / 2
                        / (((lowestDx != 1) && (lowestDy != 1)) ? ROOT_OF_TWO : 1)),
                        ERODE_AMOUNT);
                amount = (int) ((amount / 64f) * (amount / 64f) * 64);
                if (amount > 0) {
                    heights[centreIndex] -= amount;
                    heights[lowestIndex] += amount;
                    writeLog[writeIndex++] = centreIndex;
                    writeLog[writeIndex++] = heights[centreIndex];
                    writeLog[writeIndex++] = lowestIndex;
                    writeLog[writeIndex++] = heights[lowestIndex];
                }
            }
        }
        writeCount[0] = writeIndex;
    }

    private void erodeJava(Dimension dimension, int centreX, int centreY, int radius) {
        for (int i = 0; i < ROUNDS; i++) {
            for (int x = centreX - radius; x <= centreX + radius; x++) {
                for (int y = centreY - radius; y <= centreY + radius; y++) {
                    float strength = getStrength(centreX, centreY, x, y);
                    if ((strength == 1.0f) || (random.nextFloat() < strength)) {
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dy = -1; dy <= 1; dy++) {
                                heightBuffer[dx + 1][dy + 1] = dimension.getRawHeightAt(x + dx, y + dy);
                            }
                        }
                        int lowestDx = 0, lowestDy = 0, lowestRawHeight = Integer.MAX_VALUE;
                        boolean reverseX = random.nextBoolean(), reverseY = random.nextBoolean();
                        if (reverseX) {
                            for (int dx = 2; dx >= 0; dx--) {
                                if (reverseY) {
                                    for (int dy = 2; dy >= 0; dy--) {
                                        if (heightBuffer[dx][dy] < lowestRawHeight) {
                                            lowestRawHeight = heightBuffer[dx][dy];
                                            lowestDx = dx;
                                            lowestDy = dy;
                                        }
                                    }
                                } else {
                                    for (int dy = 0; dy < 3; dy++) {
                                        if (heightBuffer[dx][dy] < lowestRawHeight) {
                                            lowestRawHeight = heightBuffer[dx][dy];
                                            lowestDx = dx;
                                            lowestDy = dy;
                                        }
                                    }
                                }
                            }
                        } else {
                            for (int dx = 0; dx < 3; dx++) {
                                if (reverseY) {
                                    for (int dy = 2; dy >= 0; dy--) {
                                        if (heightBuffer[dx][dy] < lowestRawHeight) {
                                            lowestRawHeight = heightBuffer[dx][dy];
                                            lowestDx = dx;
                                            lowestDy = dy;
                                        }
                                    }
                                } else {
                                    for (int dy = 0; dy < 3; dy++) {
                                        if (heightBuffer[dx][dy] < lowestRawHeight) {
                                            lowestRawHeight = heightBuffer[dx][dy];
                                            lowestDx = dx;
                                            lowestDy = dy;
                                        }
                                    }
                                }
                            }
                        }
                        if ((lowestDx != 1) || (lowestDy != 1)) {
                            int difference = heightBuffer[1][1] - heightBuffer[lowestDx][lowestDy];
                            int amount = Math.min((int) (difference / 2 / (((lowestDx != 1) && (lowestDy != 1)) ? ROOT_OF_TWO : 1)), ERODE_AMOUNT);
                            amount = (int) ((amount / 64f) * (amount / 64f) * 64);
                            if (amount > 0) {
                                dimension.setRawHeightAt(x, y, heightBuffer[1][1] - amount);
                                dimension.setRawHeightAt(x + lowestDx - 1, y + lowestDy - 1, heightBuffer[lowestDx][lowestDy] + amount);
                            }
                        }
                    }
                }
            }
        }
    }

    private final int[][] heightBuffer = new int[3][3];
    private final Random random = new Random();
    private int nativeRadius = -1;
    private int[] nativeHeights;
    private byte[] nativeControls;
    private int[] nativeWriteLog;
    private final int[] nativeWriteCount = new int[1];
    
    private static final int ROUNDS = 1;
    private static final int ERODE_AMOUNT = 64;
    private static final float ROOT_OF_TWO = (float) Math.sqrt(2);
    private static final int MAX_NATIVE_CELLS = 1_048_576;
}
