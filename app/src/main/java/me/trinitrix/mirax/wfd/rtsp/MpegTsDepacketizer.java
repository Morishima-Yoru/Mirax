package me.trinitrix.mirax.wfd.rtsp;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;

/** Pulls H.264 access units out of Wi-Fi Display RTP (MP2T, payload type 33). */
public final class MpegTsDepacketizer {
    private int videoPid = -1;
    private int pmtPid = -1;
    private final ByteArrayOutputStream pes = new ByteArrayOutputStream();
    private int pesNeed = -1;
    private boolean pesOpen = false;
    private final ArrayDeque<byte[]> units = new ArrayDeque<byte[]>();

    public void pushRtp(byte[] packet, int length) {
        if (packet == null || length < 12) {
            return;
        }
        int version = (packet[0] >> 6) & 0x3;
        if (version != 2) {
            return;
        }
        boolean padding = ((packet[0] >> 5) & 1) != 0;
        boolean extension = ((packet[0] >> 4) & 1) != 0;
        int csrc = packet[0] & 0x0F;
        int payloadType = packet[1] & 0x7F;
        if (payloadType != 33) {
            return;
        }
        int offset = 12 + csrc * 4;
        if (extension) {
            if (offset + 4 > length) {
                return;
            }
            int extLen = ((packet[offset + 2] & 0xFF) << 8) | (packet[offset + 3] & 0xFF);
            offset += 4 + extLen * 4;
        }
        int end = length;
        if (padding) {
            int pad = packet[length - 1] & 0xFF;
            end -= pad;
        }
        while (offset + 188 <= end) {
            pushTs(packet, offset);
            offset += 188;
        }
    }

    public byte[] poll() {
        return units.poll();
    }

    private void pushTs(byte[] packet, int offset) {
        if ((packet[offset] & 0xFF) != 0x47) {
            return;
        }
        int pid = ((packet[offset + 1] & 0x1F) << 8) | (packet[offset + 2] & 0xFF);
        boolean pusi = (packet[offset + 1] & 0x40) != 0;
        int adapt = (packet[offset + 3] >> 4) & 0x3;
        int payload = offset + 4;
        int packetEnd = offset + 188;
        if (adapt == 2 || adapt == 3) {
            if (payload >= packetEnd) {
                return;
            }
            int adaptLen = packet[payload] & 0xFF;
            payload += 1 + adaptLen;
        }
        if (adapt == 2 || payload >= packetEnd) {
            return;
        }
        if (pid == 0 && pusi) {
            parsePsi(packet, payload, packetEnd, true);
            return;
        }
        if (pid == pmtPid && pusi) {
            parsePsi(packet, payload, packetEnd, false);
            return;
        }
        if (pid != videoPid || videoPid < 0) {
            return;
        }
        if (pusi) {
            finishPes();
            if (payload + 6 > packetEnd) {
                return;
            }
            if ((packet[payload] & 0xFF) != 0 || (packet[payload + 1] & 0xFF) != 0
                    || (packet[payload + 2] & 0xFF) != 1) {
                return;
            }
            int pesLen = ((packet[payload + 4] & 0xFF) << 8) | (packet[payload + 5] & 0xFF);
            int headerStart = payload + 6;
            if (headerStart + 3 > packetEnd) {
                return;
            }
            int headerLen = packet[headerStart + 2] & 0xFF;
            int data = headerStart + 3 + headerLen;
            pes.reset();
            pesOpen = true;
            pesNeed = pesLen == 0 ? 0 : pesLen - 3 - headerLen;
            if (pesNeed < 0) {
                pesNeed = 0;
            }
            appendPes(packet, data, packetEnd);
            return;
        }
        if (pesOpen) {
            appendPes(packet, payload, packetEnd);
        }
    }

    private void appendPes(byte[] packet, int from, int to) {
        if (from >= to) {
            if (pesNeed > 0 && pes.size() >= pesNeed) {
                finishPes();
            }
            return;
        }
        int take = to - from;
        if (pesNeed > 0) {
            int remain = pesNeed - pes.size();
            if (remain <= 0) {
                finishPes();
                return;
            }
            if (take > remain) {
                take = remain;
            }
        }
        pes.write(packet, from, take);
        if (pesNeed > 0 && pes.size() >= pesNeed) {
            finishPes();
        }
    }

    private void finishPes() {
        if (!pesOpen) {
            return;
        }
        pesOpen = false;
        byte[] au = pes.toByteArray();
        pes.reset();
        pesNeed = -1;
        if (au.length > 0) {
            units.add(au);
        }
    }

    private void parsePsi(byte[] packet, int payload, int packetEnd, boolean pat) {
        if (payload >= packetEnd) {
            return;
        }
        int pointer = packet[payload] & 0xFF;
        int start = payload + 1 + pointer;
        if (start + 3 >= packetEnd) {
            return;
        }
        int tableId = packet[start] & 0xFF;
        int sectionLen = ((packet[start + 1] & 0x0F) << 8) | (packet[start + 2] & 0xFF);
        int sectionEnd = start + 3 + sectionLen;
        if (sectionEnd > packetEnd) {
            sectionEnd = packetEnd;
        }
        if (pat) {
            if (tableId != 0x00) {
                return;
            }
            int pos = start + 8;
            if (pos + 4 <= sectionEnd - 4 || pos + 4 <= sectionEnd) {
                if (pos + 4 <= start + 3 + sectionLen && pos + 4 <= packetEnd) {
                    int program = ((packet[pos] & 0xFF) << 8) | (packet[pos + 1] & 0xFF);
                    int pid = ((packet[pos + 2] & 0x1F) << 8) | (packet[pos + 3] & 0xFF);
                    if (program != 0) {
                        pmtPid = pid;
                    }
                }
            }
            return;
        }
        if (tableId != 0x02) {
            return;
        }
        if (start + 12 >= packetEnd) {
            return;
        }
        int infoLen = ((packet[start + 10] & 0x0F) << 8) | (packet[start + 11] & 0xFF);
        int pos = start + 12 + infoLen;
        while (pos + 5 <= sectionEnd - 4 && pos + 5 <= packetEnd) {
            int streamType = packet[pos] & 0xFF;
            int pid = ((packet[pos + 1] & 0x1F) << 8) | (packet[pos + 2] & 0xFF);
            int esLen = ((packet[pos + 3] & 0x0F) << 8) | (packet[pos + 4] & 0xFF);
            if (streamType == 0x1B || streamType == 0x24) {
                videoPid = pid;
                return;
            }
            pos += 5 + esLen;
        }
    }

    /** Visible for tests that want a packet with a 12-byte RTP header. */
    public static byte[] rtpWrap(byte[] payload) {
        byte[] out = new byte[12 + payload.length];
        out[0] = (byte) 0x80;
        out[1] = 33;
        out[2] = 0;
        out[3] = 1;
        out[8] = 0;
        out[9] = 0;
        out[10] = 0;
        out[11] = 1;
        System.arraycopy(payload, 0, out, 12, payload.length);
        return out;
    }

    public static byte[] tsPacket(int pid, boolean pusi, int cc, byte[] payload) {
        byte[] pkt = new byte[188];
        Arrays.fill(pkt, (byte) 0xFF);
        pkt[0] = 0x47;
        pkt[1] = (byte) ((pusi ? 0x40 : 0x00) | ((pid >> 8) & 0x1F));
        pkt[2] = (byte) pid;
        pkt[3] = (byte) (0x10 | (cc & 0x0F));
        int n = Math.min(payload.length, 184);
        System.arraycopy(payload, 0, pkt, 4, n);
        return pkt;
    }
}
