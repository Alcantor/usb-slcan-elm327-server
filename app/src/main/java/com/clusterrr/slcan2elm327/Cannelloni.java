package com.clusterrr.slcan2elm327;

import com.androidcan.CanFrame;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The cannelloni wire format
 * (<a href="https://github.com/mguentner/cannelloni">mguentner/cannelloni</a>),
 * with no sockets or Android in it so it can be exercised on its own.
 *
 * <p>This is cannelloni's <em>TCP</em> mode ({@code cannelloni -C c ...}). A
 * connection opens with {@link #CONNECT_V1_STRING} exchanged in both
 * directions, after which frames are written back to back with no per-packet
 * header - the {@code version/op_code/seq_no/count} header belongs to the UDP
 * transport only, where a datagram has to say how many frames it carries. On a
 * stream each frame delimits itself:</p>
 * <pre>
 *   can_id(4, big endian)  len(1)  [flags(1) if len&amp;0x80]  [data unless RTR or len==0]
 * </pre>
 * {@code can_id} carries the SocketCAN flag bits (EFF/RTR/ERR) in its top bits.
 * Only classic CAN is produced - the androidCAN drivers are classic-only - but
 * incoming CAN FD frames are consumed in full so the stream stays aligned.
 */
public final class Cannelloni {
    /** Exchanged both ways right after connect, before any frame. */
    public final static String CONNECT_V1_STRING = "CANNELLONIv1";

    private final static int CANFD_FRAME = 0x80;

    /* SocketCAN can_id layout, see linux/can.h */
    private final static int CAN_EFF_FLAG = 0x80000000;
    private final static int CAN_RTR_FLAG = 0x40000000;
    private final static int CAN_ERR_FLAG = 0x20000000;
    private final static int CAN_SFF_MASK = 0x000007FF;
    private final static int CAN_EFF_MASK = 0x1FFFFFFF;

    private Cannelloni() {
    }

    public static byte[] handshake() {
        return CONNECT_V1_STRING.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Write one frame, the mirror of {@link #readFrame}. When the bytes
     * actually leave is the caller's business: nothing is flushed here.
     */
    public static void writeFrame(CanFrame f, DataOutputStream out) throws IOException {
        int canId = f.isExtended ? (f.id & CAN_EFF_MASK) | CAN_EFF_FLAG : (f.id & CAN_SFF_MASK);
        if (f.isRemote) canId |= CAN_RTR_FLAG;
        out.writeInt(canId);              // Network byte order, like htonl().
        out.writeByte(f.data.length);     // dlc, without the CANFD_FRAME bit
        /* RTR frames carry a dlc but no data section. */
        if (!f.isRemote) out.write(f.data);
    }

    /**
     * Read exactly one frame, blocking until it has arrived in full.
     *
     * <p>Reading blocking on a dedicated thread is what lets this stay a plain
     * sequence of reads; cannelloni itself needs an explicit {@code expectedBytes}
     * state machine only because it multiplexes its sockets with {@code select}.</p>
     *
     * @return the frame, or {@code null} if it was one this bridge cannot put on
     *         a classic bus (CAN FD, or an error frame from the peer). Its bytes
     *         are consumed either way, so the stream stays aligned.
     * @throws java.io.EOFException when the peer closed the connection.
     */
    public static CanFrame readFrame(DataInputStream in) throws IOException {
        int canId = in.readInt(); // Network byte order, like ntohl().
        int len = in.readUnsignedByte();
        boolean fd = (len & CANFD_FRAME) != 0;
        len &= ~CANFD_FRAME;
        if (fd) in.readUnsignedByte(); // CAN FD flags byte.

        boolean rtr = (canId & CAN_RTR_FLAG) != 0;
        byte[] data = new byte[0];
        /* No data section for RTR, nor for an empty payload. */
        if (!rtr && len > 0) {
            data = new byte[len];
            in.readFully(data);
        }

        /* Error frames are diagnostics from the far end, not bus traffic, and
         * CAN FD payloads don't fit a classic frame. Skip both - consumed. */
        if ((canId & CAN_ERR_FLAG) != 0 || fd || len > 8) return null;

        boolean extended = (canId & CAN_EFF_FLAG) != 0;
        int id = extended ? (canId & CAN_EFF_MASK) : (canId & CAN_SFF_MASK);
        return new CanFrame(id, extended, rtr, data);
    }
}
