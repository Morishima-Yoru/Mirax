package com.secondscreen.wfd;

/**
 * One-block EDID whose preferred detailed timing matches {@link Capabilities}.
 * Timings use CVT reduced blanking: 160-pixel horizontal blank and a vertical blank of 52 lines,
 * which keeps the vertical blanking interval at about 460 microseconds.
 */
public final class Edid {
    public static final int H_BLANK = 160;
    public static final int V_BLANK = 52;
    public static final int H_SYNC_OFFSET = 48;
    public static final int H_SYNC_WIDTH = 32;
    public static final int V_SYNC_OFFSET = 3;
    public static final int V_SYNC_WIDTH = 6;
    public static final int H_IMAGE_MM = 146;
    public static final int V_IMAGE_MM = 122;

    private Edid() {}

    public static int pixelClock10kHz() {
        int hTotal = Capabilities.WIDTH + H_BLANK;
        int vTotal = Capabilities.HEIGHT + V_BLANK;
        long hz = (long) hTotal * vTotal * Capabilities.FPS;
        return (int) ((hz + 5000L) / 10000L);
    }

    public static byte[] block() {
        byte[] e = new byte[128];
        e[0] = 0x00;
        e[1] = (byte) 0xFF;
        e[2] = (byte) 0xFF;
        e[3] = (byte) 0xFF;
        e[4] = (byte) 0xFF;
        e[5] = (byte) 0xFF;
        e[6] = (byte) 0xFF;
        e[7] = 0x00;
        // Manufacturer "ZFD"
        e[8] = 0x68;
        e[9] = (byte) 0xC4;
        e[10] = 0x01;
        e[11] = 0x00;
        e[16] = 27;
        e[17] = (byte) (2026 - 1990);
        e[18] = 1;
        e[19] = 3;
        e[20] = (byte) 0x80;
        e[21] = 15;
        e[22] = 12;
        e[23] = 120;
        e[24] = 0x0A;
        e[25] = (byte) 0xEE;
        e[26] = (byte) 0x91;
        e[27] = (byte) 0xA3;
        e[28] = 0x54;
        e[29] = 0x4C;
        e[30] = (byte) 0x99;
        e[31] = 0x26;
        e[32] = 0x0F;
        e[33] = 0x50;
        e[34] = 0x54;
        for (int i = 38; i < 54; i += 2) {
            e[i] = 0x01;
            e[i + 1] = 0x01;
        }
        putTiming(e, 54);
        putDescriptor(e, 72, (byte) 0xFC, "ZFold5");
        putRange(e, 90);
        putDescriptorHeader(e, 108, (byte) 0x10);
        int sum = 0;
        for (int i = 0; i < 127; i++) {
            sum = (sum + (e[i] & 0xFF)) & 0xFF;
        }
        e[127] = (byte) ((256 - sum) & 0xFF);
        return e;
    }

    public static String parameterValue() {
        byte[] block = block();
        StringBuilder hex = new StringBuilder(5 + block.length * 2);
        hex.append("0001 ");
        for (int i = 0; i < block.length; i++) {
            hex.append(String.format("%02X", block[i] & 0xFF));
        }
        return hex.toString();
    }

    private static void putTiming(byte[] e, int o) {
        int clock = pixelClock10kHz();
        int ha = Capabilities.WIDTH;
        int hb = H_BLANK;
        int va = Capabilities.HEIGHT;
        int vb = V_BLANK;
        int hso = H_SYNC_OFFSET;
        int hsw = H_SYNC_WIDTH;
        int vso = V_SYNC_OFFSET;
        int vsw = V_SYNC_WIDTH;
        e[o] = (byte) (clock & 0xFF);
        e[o + 1] = (byte) ((clock >> 8) & 0xFF);
        e[o + 2] = (byte) (ha & 0xFF);
        e[o + 3] = (byte) (hb & 0xFF);
        e[o + 4] = (byte) ((((ha >> 8) & 0x0F) << 4) | ((hb >> 8) & 0x0F));
        e[o + 5] = (byte) (va & 0xFF);
        e[o + 6] = (byte) (vb & 0xFF);
        e[o + 7] = (byte) ((((va >> 8) & 0x0F) << 4) | ((vb >> 8) & 0x0F));
        e[o + 8] = (byte) (hso & 0xFF);
        e[o + 9] = (byte) (hsw & 0xFF);
        e[o + 10] = (byte) (((vso & 0x0F) << 4) | (vsw & 0x0F));
        e[o + 11] = (byte) ((((hso >> 8) & 0x03) << 6)
                | (((hsw >> 8) & 0x03) << 4)
                | (((vso >> 4) & 0x03) << 2)
                | ((vsw >> 4) & 0x03));
        e[o + 12] = (byte) (H_IMAGE_MM & 0xFF);
        e[o + 13] = (byte) (V_IMAGE_MM & 0xFF);
        e[o + 14] = (byte) ((((H_IMAGE_MM >> 8) & 0x0F) << 4) | ((V_IMAGE_MM >> 8) & 0x0F));
        e[o + 17] = 0x1E;
    }

    private static void putDescriptorHeader(byte[] e, int o, byte tag) {
        e[o + 3] = tag;
    }

    private static void putDescriptor(byte[] e, int o, byte tag, String text) {
        putDescriptorHeader(e, o, tag);
        byte[] raw = text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int n = Math.min(13, raw.length);
        System.arraycopy(raw, 0, e, o + 5, n);
        if (n < 13) {
            e[o + 5 + n] = 0x0A;
            for (int i = n + 1; i < 13; i++) {
                e[o + 5 + i] = 0x20;
            }
        }
    }

    private static void putRange(byte[] e, int o) {
        putDescriptorHeader(e, o, (byte) 0xFD);
        e[o + 5] = 30;
        e[o + 6] = 75;
        e[o + 7] = 50;
        e[o + 8] = 120;
        e[o + 9] = 27;
        e[o + 11] = 0x0A;
        for (int i = 12; i < 18; i++) {
            e[o + i] = 0x20;
        }
    }
}
