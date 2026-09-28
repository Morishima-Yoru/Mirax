package com.secondscreen.wfd;

/**
 * CEA, VESA and HH modes from the Wi-Fi Display resolution bitmaps, in index order.
 * A source that does not understand {@code microsoft_custom_video_formats} selects one of these.
 */
public final class VideoModes {
    private static final int[][] CEA = {
            {640, 480, 60},
            {720, 480, 60},
            {720, 480, 60},
            {720, 576, 50},
            {720, 576, 50},
            {1280, 720, 30},
            {1280, 720, 60},
            {1920, 1080, 30},
            {1920, 1080, 60},
            {1920, 1080, 60},
            {1280, 720, 25},
            {1280, 720, 50},
            {1920, 1080, 25},
            {1920, 1080, 50},
            {1280, 720, 24},
            {1920, 1080, 24},
    };

    private static final int[][] VESA = {
            {800, 600, 30}, {800, 600, 60},
            {1024, 768, 30}, {1024, 768, 60},
            {1152, 864, 30}, {1152, 864, 60},
            {1280, 768, 30}, {1280, 768, 60},
            {1280, 800, 30}, {1280, 800, 60},
            {1360, 768, 30}, {1360, 768, 60},
            {1366, 768, 30}, {1366, 768, 60},
            {1280, 1024, 30}, {1280, 1024, 60},
            {1400, 1050, 30}, {1400, 1050, 60},
            {1440, 900, 30}, {1440, 900, 60},
            {1600, 900, 30}, {1600, 900, 60},
            {1600, 1200, 30}, {1600, 1200, 60},
            {1680, 1024, 30}, {1680, 1024, 60},
            {1680, 1050, 30}, {1680, 1050, 60},
            {1920, 1200, 30}, {1920, 1200, 60},
    };

    private static final int[][] HH = {
            {800, 480, 30}, {800, 480, 60},
            {854, 480, 30}, {854, 480, 60},
            {864, 480, 30}, {864, 480, 60},
            {640, 360, 30}, {640, 360, 60},
            {960, 540, 30}, {960, 540, 60},
            {848, 480, 30}, {848, 480, 60},
    };

    private VideoModes() {}

    /** Highest-pixel progressive-looking mode whose bit is set, or null. */
    public static int[] fromBitmapValue(String value) {
        if (value == null) {
            return null;
        }
        long cea = -1;
        long vesa = -1;
        long hh = -1;
        int masks = 0;
        String[] parts = value.trim().split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            String token = parts[i];
            int length = token.length();
            if (length != 8 && length != 10 && length != 12) {
                continue;
            }
            if (!token.matches("[0-9A-Fa-f]+")) {
                continue;
            }
            long mask;
            try {
                mask = Long.parseLong(token, 16);
            } catch (NumberFormatException ex) {
                continue;
            }
            if (masks == 0) {
                cea = mask;
            } else if (masks == 1) {
                vesa = mask;
            } else if (masks == 2) {
                hh = mask;
            }
            masks++;
        }
        int[] chosen = null;
        chosen = prefer(chosen, best(cea, CEA));
        chosen = prefer(chosen, best(vesa, VESA));
        chosen = prefer(chosen, best(hh, HH));
        return chosen;
    }

    private static int[] prefer(int[] current, int[] candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        int currentPixels = current[0] * current[1];
        int candidatePixels = candidate[0] * candidate[1];
        if (candidatePixels > currentPixels) {
            return candidate;
        }
        if (candidatePixels == currentPixels && candidate[2] > current[2]) {
            return candidate;
        }
        return current;
    }

    private static int[] best(long mask, int[][] table) {
        if (mask <= 0) {
            return null;
        }
        int[] chosen = null;
        int pixels = -1;
        int limit = Math.min(table.length, 48);
        for (int i = 0; i < limit; i++) {
            if ((mask & (1L << i)) == 0) {
                continue;
            }
            int[] mode = table[i];
            int area = mode[0] * mode[1];
            if (chosen == null || area > pixels || (area == pixels && mode[2] > chosen[2])) {
                chosen = mode;
                pixels = area;
            }
        }
        return chosen;
    }
}
