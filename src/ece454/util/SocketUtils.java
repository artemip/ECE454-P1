package ece454.util;

import ece454.ece454;

import java.net.Socket;

public class SocketUtils {
    public static boolean isSocketOpen(Socket socket) {
        return socket != null && !socket.isClosed() && socket.isConnected();
    }
}