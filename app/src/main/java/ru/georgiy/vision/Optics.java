package ru.georgiy.vision;

final class Optics {
    static final int NORMAL = 0;
    static final int MYOPIA = 1;
    static final int ASTIGMATISM = 2;
    static final int HAZE = 3;
    static final int SHARP = 4;
    static final int AUTOMATIC = -1;
    static final int ALL_FILTERS = filterBit(MYOPIA) | filterBit(ASTIGMATISM) | filterBit(HAZE);
    static final float PUPIL_RADIUS_METERS = 0.002f;
    static final int MAX_DEPTH_VERGENCE = 15;

    // The selected distance applies to the entire frame. Zero denotes optical infinity.
    static final float[] DISTANCES_METERS = {0f, 0.30f, 0.15f};

    static int farPointCentimeters(int diopters) {
        return Math.round(100f / diopters);
    }

    static int filterBit(int filter) {
        return 1 << (filter - 1);
    }

    static float manualVergence(int distanceIndex) {
        if (distanceIndex == AUTOMATIC) return 0f;
        float distance = DISTANCES_METERS[distanceIndex];
        return distance == 0f ? 0f : 1f / distance;
    }

    static byte encodeDepth(int millimeters) {
        if (millimeters == 0) return 0;
        return (byte) Math.min(255, Math.round(255_000f / (MAX_DEPTH_VERGENCE * millimeters)));
    }

    private Optics() {}
}
