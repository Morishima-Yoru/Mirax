package me.trinitrix.mirax.wfd.rtsp;

/**
 * Reads visible width and height out of an H.264 SPS NAL, including frame cropping.
 * Used to confirm the stream Windows actually sent.
 */
public final class SpsParser {
    public static final class Size {
        public final int width;
        public final int height;

        public Size(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private SpsParser() {}

    public static Size parseAnnexB(byte[] data) {
        if (data == null) {
            return null;
        }
        int i = 0;
        while (i + 4 < data.length) {
            int start = -1;
            int header = 0;
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                start = i + 3;
                header = 3;
            } else if (i + 4 < data.length
                    && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
                start = i + 4;
                header = 4;
            }
            if (start < 0) {
                i++;
                continue;
            }
            int next = start;
            while (next + 3 < data.length) {
                if (data[next] == 0 && data[next + 1] == 0
                        && (data[next + 2] == 1
                        || (data[next + 2] == 0 && next + 3 < data.length && data[next + 3] == 1))) {
                    break;
                }
                next++;
            }
            if (next + 3 >= data.length) {
                next = data.length;
            }
            int nalType = data[start] & 0x1F;
            if (nalType == 7) {
                Size size = parseNal(data, start, next - start);
                if (size != null) {
                    return size;
                }
            }
            if (header == 0) {
                i++;
            } else {
                i = next;
            }
        }
        return null;
    }

    public static Size parseNal(byte[] nal, int off, int len) {
        try {
            byte[] rbsp = ebspToRbsp(nal, off, len);
            if (rbsp.length < 4) {
                return null;
            }
            BitReader br = new BitReader(rbsp);
            int nalHeader = br.u(8);
            if ((nalHeader & 0x1F) != 7) {
                return null;
            }
            int profile = br.u(8);
            br.u(8);
            br.u(8);
            br.ue();
            int chroma = 1;
            if (isHighProfile(profile)) {
                chroma = br.ue();
                if (chroma == 3) {
                    br.u(1);
                }
                br.ue();
                br.ue();
                br.u(1);
                if (br.u(1) == 1) {
                    int count = chroma != 3 ? 8 : 12;
                    for (int i = 0; i < count; i++) {
                        if (br.u(1) == 1) {
                            skipScalingList(br, i < 6 ? 16 : 64);
                        }
                    }
                }
            }
            br.ue();
            int pocType = br.ue();
            if (pocType == 0) {
                br.ue();
            } else if (pocType == 1) {
                br.u(1);
                br.se();
                br.se();
                int cycles = br.ue();
                for (int i = 0; i < cycles; i++) {
                    br.se();
                }
            }
            br.ue();
            br.u(1);
            int widthMbs = br.ue() + 1;
            int heightMaps = br.ue() + 1;
            int frameMbsOnly = br.u(1);
            if (frameMbsOnly == 0) {
                br.u(1);
            }
            br.u(1);
            int width = widthMbs * 16;
            int height = heightMaps * 16 * (frameMbsOnly == 1 ? 1 : 2);
            if (br.u(1) == 1) {
                int left = br.ue();
                int right = br.ue();
                int top = br.ue();
                int bottom = br.ue();
                int cropX = 1;
                int cropY = 1;
                if (chroma == 1) {
                    cropX = 2;
                    cropY = 2;
                } else if (chroma == 2) {
                    cropX = 2;
                    cropY = 1;
                }
                width -= (left + right) * cropX;
                height -= (top + bottom) * cropY * (frameMbsOnly == 1 ? 1 : 2);
            }
            if (width <= 0 || height <= 0) {
                return null;
            }
            return new Size(width, height);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean isHighProfile(int profile) {
        switch (profile) {
            case 100:
            case 110:
            case 122:
            case 244:
            case 44:
            case 83:
            case 86:
            case 118:
            case 128:
            case 138:
            case 139:
            case 134:
            case 135:
                return true;
            default:
                return false;
        }
    }

    private static void skipScalingList(BitReader br, int size) {
        int last = 8;
        int next = 8;
        for (int j = 0; j < size; j++) {
            if (next != 0) {
                int delta = br.se();
                next = (last + delta + 256) % 256;
            }
            last = next == 0 ? last : next;
        }
    }

    static byte[] ebspToRbsp(byte[] nal, int off, int len) {
        byte[] out = new byte[len];
        int n = 0;
        int zeros = 0;
        for (int i = 0; i < len; i++) {
            int b = nal[off + i] & 0xFF;
            if (zeros >= 2 && b == 0x03) {
                zeros = 0;
                continue;
            }
            out[n++] = (byte) b;
            zeros = b == 0 ? zeros + 1 : 0;
        }
        byte[] trimmed = new byte[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    static final class BitReader {
        private final byte[] data;
        private int bit;

        BitReader(byte[] data) {
            this.data = data;
        }

        int u(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                int byteIndex = bit >> 3;
                if (byteIndex >= data.length) {
                    throw new IllegalStateException("truncated");
                }
                int shift = 7 - (bit & 7);
                v = (v << 1) | ((data[byteIndex] >> shift) & 1);
                bit++;
            }
            return v;
        }

        int ue() {
            int zeros = 0;
            while (u(1) == 0) {
                zeros++;
                if (zeros > 31) {
                    throw new IllegalStateException("exp-golomb");
                }
            }
            if (zeros == 0) {
                return 0;
            }
            return ((1 << zeros) | u(zeros)) - 1;
        }

        int se() {
            int code = ue();
            int value = (code + 1) >> 1;
            return (code & 1) == 0 ? -value : value;
        }
    }
}
