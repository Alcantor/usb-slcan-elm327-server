package com.clusterrr.slcan2elm327;

import com.androidcan.CanFrame;
import com.androidcan.FrameListener;
import com.androidcan.ReceivedFrame;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * CAN over TCP using the cannelloni protocol, the replacement for the former
 * SLCAN-over-TCP server. The wire format itself lives in {@link Cannelloni};
 * this is the socket around it.
 *
 * <p>This is the server half, so it listens and the peer dials in
 * ({@code cannelloni -I can0 -C c -R <phone> -r <port>}). One client at a time,
 * as with the ELM327 server: a second CAN tunnel onto the same bus would only
 * duplicate traffic.</p>
 *
 * <p>{@link #onFrameReceived} only queues, because it runs on the USB RX
 * thread that also feeds the ELM327 server. Writing there
 * would be fine almost always - a full CAN bus is around 50 kB/s of cannelloni
 * against a socket send buffer of 64 kB or more - but a peer that stops reading
 * (a sleeping laptop, a dropped link) fills that buffer within seconds, and the
 * write would then block the RX thread, and with it the ELM327 server, until
 * TCP gives up minutes later. The queue confines that to this tunnel: frames
 * are dropped instead. Nothing else here needs a thread.</p>
 */
public class CannelloniServer implements FrameListener {
    private final static int TX_QUEUE_SIZE = 256;

    private final Service service;
    private final int port;
    /** Whether frames may travel bus -> client, and client -> bus. */
    private final boolean toClient;
    private final boolean toBus;

    private final BlockingQueue<CanFrame> txQueue;
    private volatile ServerSocket serverSock;
    private volatile Socket sock;
    private DataInputStream in;
    /**
     * Never nulled once a client has connected: {@link #negotiated} says
     * whether it is worth writing to, and a stream left over from a closed
     * connection throws IOException rather than needing a null check.
     */
    private volatile DataOutputStream out;
    private Thread rxThread, txThread;
    private volatile boolean negotiated;
    private volatile boolean running;

    public CannelloniServer(Service service, int port, int mode) {
        this.service = service;
        this.port = port;
        this.toClient = mode == Service.NET_MODE_BOTH || mode == Service.NET_MODE_FROM_BUS;
        this.toBus = mode == Service.NET_MODE_BOTH || mode == Service.NET_MODE_TO_BUS;
        this.txQueue = new ArrayBlockingQueue<>(TX_QUEUE_SIZE);
        this.sock = null;
        this.out = null;
        this.negotiated = false;
        this.running = true;
    }

    public void start() {
        txThread = new Thread(this::transmit, "cannelloni-tx");
        rxThread = new Thread(this::receive, "cannelloni-rx");
        txThread.start();
        rxThread.start();
    }

    /**
     * cannelloni's TCP greeting: send our version string, then require the
     * peer's to match before a single frame is exchanged.
     */
    private boolean negotiate() throws IOException {
        byte[] hello = Cannelloni.handshake();
        out.write(hello);
        out.flush();
        byte[] peer = new byte[hello.length];
        in.readFully(peer);
        return Arrays.equals(peer, hello);
    }

    private void receive(){
        try {
            serverSock = new ServerSocket(port);
            while (running) {
                service.statusUpdateNet(service.getString(R.string.net_wait) + service.localIp);
                sock = serverSock.accept();
                try {
                    sock.setTcpNoDelay(true);
                    in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
                    out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream()));

                    if (!negotiate()) {
                        service.statusUpdateNet(service.getString(R.string.net_protocol));
                        continue; // closeClient() in the finally below.
                    }

                    txQueue.clear(); // Don't open with frames from the last session.
                    negotiated = true;
                    service.statusUpdateNet(service.getString(R.string.net_connected) + sock.getRemoteSocketAddress());

                    /* Until the peer disconnects, which surfaces as EOFException. */
                    while (running) {
                        /* Read regardless of direction: dropping the frame is
                         * the mode's business, but leaving it unread would fill
                         * the socket and stall the client's sending. */
                        CanFrame f = Cannelloni.readFrame(in);
                        if (f != null && toBus) service.usbCan.sendCAN(f);
                    }
                } catch (Exception e) {
                    if (running) e.printStackTrace();
                } finally {
                    closeClient();
                }
            }
        } catch (Exception e) {
            if (running) e.printStackTrace();
        }
    }

    /**
     * Drain queued frames onto the stream. Each one is written straight into
     * the BufferedOutputStream and nothing is flushed until the queue has run
     * dry, so a burst leaves as a single write while a lone frame on a quiet
     * bus still goes out immediately - the buffering the stream already does,
     * rather than a second copy on top of it.
     *
     * <p>The queue is not about batching, though: it is what keeps a stalled
     * peer off the USB RX thread that {@link #onFrameReceived} runs on.</p>
     */
    private void transmit() {
        while (running) {
            try {
                CanFrame f = txQueue.take(); // close() interrupts to break this.
                /* Checked after take(), never before: take() is what parks this
                 * thread while no client is connected. Testing first would spin
                 * a core flat out for the whole time nobody is dialled in. */
                if (!negotiated) continue; // Left over from a session that ended.
                do {
                    Cannelloni.writeFrame(f, out);
                } while ((f = txQueue.poll()) != null);
                out.flush(); // Nothing left to add: push the burst.
                service.statusUpdateNet(service.getString(R.string.net_transmit));
            } catch (IOException e) {
                service.statusUpdateNet(service.getString(R.string.net_error));
            } catch (InterruptedException e) {
                break; // close() asked us to stop.
            }
        }
    }

    /**
     * Function called by the androidCAN driver's RX thread.
     * @param received CAN Frame received from device.
     */
    @Override
    public void onFrameReceived(ReceivedFrame received) {
        if (!toClient) return;   // This direction is switched off.
        if (!negotiated) return; // Nobody is listening, don't fill the queue.
        txQueue.offer(received.frame); // Dropping is better than blocking the RX thread.
    }

    /**
     * Drop the current client. Closing the socket is enough: it closes the
     * streams under it, whereas closing the BufferedOutputStream would flush
     * first, and flushing to a peer that has stopped reading is exactly what
     * blocks. Never throws, so close() can call it without a guard.
     */
    private void closeClient() {
        negotiated = false; // The one gate: stops the TX thread writing.
        /* Taken and cleared in one go: the rx thread's finally and close() can
         * both land here at once, and re-reading the field between the test and
         * the close would let one of them NullPointerException.
         *
         * out is deliberately left pointing at the closed stream. A write that
         * slipped past the gate above then throws IOException, which is
         * handled, instead of a NullPointerException, which is not. */
        Socket s = sock;
        sock = null;
        if (s == null) return;
        try {
            s.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void close() {
        try {
            service.statusUpdateNet(service.getString(R.string.net_stopping));
            running = false;
            negotiated = false;
            /* Closing is what actually stops the threads: neither running=false
             * nor interrupt() disturbs a blocking socket read, so the RX thread
             * only leaves readFrame() when the socket under it goes. It then
             * runs its own finally, which calls closeClient() a second time -
             * harmless, Socket.close() ignores an already-closed socket. */
            if (serverSock != null) serverSock.close(); // Unblocks accept().
            closeClient();                              // Unblocks readFrame() and a stuck write().
            if (txThread != null) {
                txThread.interrupt();
                txThread.join();
                txThread = null;
            }
            if (rxThread != null) {
                rxThread.join();
                rxThread = null;
            }
            service.statusUpdateNet(service.getString(R.string.net_stopped));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
