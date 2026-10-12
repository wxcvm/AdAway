package org.adaway.model.traffic;

import java.io.IOException;
import java.net.DatagramSocket;
import java.util.HashSet;
import java.util.Set;

/** Closing the service immediately cancels socket receives, including racing opens. */
public final class DiagnosticSockets implements AutoCloseable {
    private final Set<DatagramSocket> sockets = new HashSet<>();
    private boolean closed;
    public synchronized void register(DatagramSocket socket) throws IOException {
        if (closed) { socket.close(); throw new IOException("Diagnostic stopped"); }
        sockets.add(socket);
    }
    public synchronized void remove(DatagramSocket socket) { sockets.remove(socket); }
    @Override public synchronized void close() {
        closed = true;
        for (DatagramSocket socket : sockets) socket.close();
        sockets.clear();
    }
}
