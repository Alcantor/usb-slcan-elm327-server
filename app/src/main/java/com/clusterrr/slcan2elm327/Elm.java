package com.clusterrr.slcan2elm327;

import com.androidcan.CanFrame;

/**
 * ELM327 text encoding of CAN frames, plus the handful of ISO 15765-2 (ISO-TP)
 * PCI predicates the ELM327 emulation needs to decide when a response is
 * complete and when to answer with a flow control frame.
 *
 * <p>Frames themselves are {@link CanFrame} from the androidCAN library, so
 * nothing has to be converted between the USB driver and the servers.</p>
 */
public final class Elm {

    private Elm() {
    }

    /**
     * Build a CAN frame from the hex payload of an ELM327 data command.
     * A single-frame ISO-TP PCI byte is prepended, so at most 7 payload bytes
     * fit; standard ELM327 has no multi-frame transmit either.
     *
     * @return the frame, or {@code null} if the payload does not fit.
     */
    public static CanFrame fromELM(int id, boolean idExtended, String s) {
        int len = s.length() / 2;
        /* TODO: Implement RTR Frame */
        /* Standard ELM327 doesn't support ISO-15765-2 multi frame payloads. */
        if (len <= 7) {
            byte[] data = new byte[len + 1];
            data[0] = (byte) (len & 0x0F); /* PCI byte */
            for (int i = 0; i < len; ++i) {
                int i2 = i * 2;
                data[i + 1] = (byte) Integer.parseInt(s.substring(i2, i2 + 2), 16);
            }
            return new CanFrame(id, idExtended, false, data);
        }
        return null;
    }

    /**
     * The ISO 15765-2 flow control frame that answers a first frame: PCI 0x30
     * is "clear to send", then a block size of 0 (send everything without
     * waiting for another of these) and a separation time of 0 (no minimum gap
     * between consecutive frames).
     */
    public static CanFrame flowControl(int id, boolean idExtended) {
        return new CanFrame(id, idExtended, false, new byte[] {0x30, 0x00, 0x00});
    }

    /**
     * Render a received frame the way an ELM327 would print it: either the raw
     * bytes prefixed by the CAN id (headers on), or the ISO-TP payload with the
     * PCI bytes stripped and multi-frame responses split into numbered lines.
     */
    public static String toELM(CanFrame f, boolean header, boolean space, boolean linefeed) {
        StringBuilder s = new StringBuilder(40);
        /* TODO: Implement RTR Frame */
        byte[] data = f.data;
        int i = 0; /* Include PCI byte(s) */
        if (header) {
            if (f.isExtended) s.append(String.format("%08X", f.id));
            else s.append(String.format("%03X", f.id));
            if (space) s.append(' ');
        } else {
            if (isSingleFrame(f)) {
                i = 1; /* Exclude PCI: 1 byte */
            } else if (isFirstFrame(f)) {
                s.append(String.format("%03X\r", (data[0] & 0x0F) << 8 | data[1]));
                if (linefeed) s.append('\n');
                s.append("0:");
                if (space) s.append(' ');
                i = 2; /* Exclude PCI: 2 bytes */
            } else if (isConsecutiveFrame(f)) {
                s.append(String.format("%1X:", data[0] & 0x0F));
                if (space) s.append(' ');
                i = 1; /* Exclude PCI: 1 byte */
            }
        }
        for (; i < data.length; ++i) {
            s.append(String.format("%02X", data[i]));
            if (space) s.append(' ');
        }
        s.append('\r');
        if (linefeed) s.append('\n');
        return s.toString();
    }

    public static boolean isSingleFrame(CanFrame f) {
        return f.data.length >= 1 && (f.data[0] >> 4) == 0;
    }

    public static boolean isFirstFrame(CanFrame f) {
        return f.data.length >= 2 && (f.data[0] >> 4) == 1;
    }

    public static boolean isConsecutiveFrame(CanFrame f) {
        return f.data.length >= 1 && (f.data[0] >> 4) == 2;
    }

    public static boolean isFlowFrame(CanFrame f) {
        return f.data.length >= 1 && (f.data[0] >> 4) == 3;
    }
}
