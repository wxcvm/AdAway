package org.adaway.model.traffic;

import java.io.IOException;
import java.io.Closeable;
import java.net.DatagramSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;

/** Closing the service immediately cancels socket receives, including racing opens. */
public final class DiagnosticSockets implements AutoCloseable {
    private final Set<Closeable> sockets = new HashSet<>();
    private boolean closed;
    public synchronized void register(DatagramSocket socket) throws IOException {
        if (closed) { socket.close(); throw new IOException("Diagnostic stopped"); }
        sockets.add(socket);
    }
    public synchronized void remove(DatagramSocket socket) { sockets.remove(socket); }
    public synchronized void register(Socket socket) throws IOException {
        if (closed) { socket.close(); throw new IOException("Diagnostic stopped"); }
        sockets.add(socket);
    }
    public synchronized void remove(Socket socket) { sockets.remove(socket); }
    @Override public synchronized void close() {
        closed = true;
        for (Closeable socket : sockets) {
            try { socket.close(); } catch (IOException ignored) { /* Continue closing every socket. */ }
        }
        sockets.clear();
    }
}
