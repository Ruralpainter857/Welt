package org.pepsoft.worldpainter.objects;

import org.pepsoft.worldpainter.Platform;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import javax.vecmath.Point3i;
import java.util.ArrayList;
import java.util.Collections;

/** Collect an immutable transform chain before doing one native voxel transaction. */
final class CompactObjectTransforms {
    private record Step(int operation, Platform platform, Point3i dimensions) {}

    static CompactMaterialVolume capture(WPObject root) {
        if (!NativeSlices.isObjectTransformsAvailable()) return null;
        ArrayList<Step> steps = new ArrayList<>();
        WPObject current = root;
        while (current.getClass() == RotatedObject.class || current.getClass() == MirroredObject.class) {
            if (steps.size() == 64) return null;
            if (current.getClass() == RotatedObject.class) {
                RotatedObject rotation = (RotatedObject) current;
                steps.add(new Step(rotation.compactOperation(), rotation.compactPlatform(), new Point3i(rotation.compactDimensions())));
                current = rotation.compactSource();
            } else {
                MirroredObject mirror = (MirroredObject) current;
                steps.add(new Step(mirror.compactOperation(), mirror.compactPlatform(), new Point3i(mirror.compactDimensions())));
                current = mirror.compactSource();
            }
        }
        // Random or mutable sources and overridden wrapper implementations retain Java traversal.
        if (!(current instanceof GenericObject)) return null;
        GenericObject source = (GenericObject) current;
        CompactMaterialVolume volume = source.compactVolume();
        if (volume == null) return null;
        Collections.reverse(steps);
        Point3i size = source.getDimensions();
        int width = size.x, length = size.y;
        for (Step step : steps) {
            if (step.operation == 1 || step.operation == 3) {
                int swap = width; width = length; length = swap;
            }
            if (!step.dimensions.equals(new Point3i(width, length, size.z))) return null;
        }
        if (!root.getDimensions().equals(new Point3i(width, length, size.z))) return null;
        int[] parameters = new int[4 + steps.size()];
        parameters[0] = size.x; parameters[1] = size.y; parameters[2] = size.z;
        Platform[] platforms = new Platform[steps.size()];
        for (int i = 0; i < steps.size(); i++) {
            parameters[i + 4] = steps.get(i).operation;
            platforms[i] = steps.get(i).platform;
        }
        return volume.transform(parameters, platforms);
    }
}