package ece454;

import ece454.messages.Message;
import ece454.util.SocketUtils;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class MessageSender extends Thread {
    private class SocketMessagingThread extends Thread {
        private Socket socket = null;
        private ObjectOutputStream messageOutputStream = null;
        private OutputStream socketOutputStream = null;
        private LinkedBlockingQueue<Message> messageQueue = null;
        private PeerDefinition recipient;
        private boolean finished = false;

        public SocketMessagingThread(Socket socket, PeerDefinition recipient) {
            this.socket = socket;
            this.recipient = recipient;
            this.messageQueue = new LinkedBlockingQueue<Message>();

            if (SocketUtils.isSocketOpen(this.socket))
                getOutputStreamFromSocket();
        }

        @Override
        public void run() {
            try {
                while (!finished) {
                    Message msg = null;
                    synchronized (this) {
                        while ((msg = messageQueue.poll()) == null) {
                            this.wait();
                        }
                    }

                    // Attempt to connect to and send a socket a message
                    try {
                        if (!SocketUtils.isSocketOpen(this.socket)) {
                            resetSocket(recipient); //Re-connect
                        }

                        if (SocketUtils.isSocketOpen(this.socket))
                            messageOutputStream.writeObject(msg);
                    } catch (IOException e) {
                        System.err.println("Could not send message to host " + recipient.getFullAddress() + ": " + e);

                        // Reset after unsuccessful connection attempt
                        resetSocket(recipient);
                    }
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        public Socket getSocket() {
            return socket;
        }

        public void resetSocket(PeerDefinition server) {
            try {
                this.socket = new Socket(server.getIPAddress(), server.getPort());
                getOutputStreamFromSocket();
            } catch (IOException e) {
                this.socket = null;
            }
        }

        public void writeMessage(Message message) {
            messageQueue.add(message);
            this.wakeup();
        }

        public void wakeup() {
            synchronized (this) {
                this.notifyAll();
            }
        }

        public void shutdown() {
            this.finished = true;
            wakeup();
        }

        private void getOutputStreamFromSocket() {
            try {
                socketOutputStream = socket.getOutputStream();
                messageOutputStream = new ObjectOutputStream(socketOutputStream);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private ConcurrentLinkedQueue<Message> messagesToSend;
    private final Map<Integer, SocketMessagingThread> peerSocketsMap;
    private boolean stopSending = false;

    public MessageSender() {
        peerSocketsMap = new ConcurrentHashMap<Integer, SocketMessagingThread>();
        messagesToSend = new ConcurrentLinkedQueue<Message>();
    }

    @Override
    public void start() {
        initPeerSockets();
        super.start();
    }

    public void initPeerSockets() {
        peerSocketsMap.clear();

        for (PeerDefinition pd : PeersList.getPeers()) {
            addPeerSocket(pd);
        }
    }

    public void addPeerSocket(PeerDefinition pd) {
        Socket s = null;
        try {
            s = new Socket(pd.getIPAddress(), pd.getPort());
        } catch (IOException e) {
            System.err.println("Host at " + pd.getFullAddress() + " has not been started. Cannot establish socket connection.");
        }

        SocketMessagingThread msgThread = new SocketMessagingThread(s, pd);
        peerSocketsMap.put(pd.getId(), msgThread);
        msgThread.start();
    }

    public void removePeerSocket(int peerId) {
        peerSocketsMap.remove(peerId);
    }

    @Override
    public void run() {
        Message msg;
        try {
            while (!stopSending) {
                //Process work queue

                synchronized (this) {
                    // While nothing to process, wait
                    while ((msg = messagesToSend.poll()) == null) {
                        wait();
                    }
                }

                SocketMessagingThread messagingThread = peerSocketsMap.get(msg.getRecipient().getId());
                if(messagingThread != null) {
                        messagingThread.writeMessage(msg);
                } else {
                    System.err.println("Could not find messaging thread for " + msg.getRecipient().getFullAddress());
                }
            }
        } catch (InterruptedException e) {
            System.err.println("Exiting work queue loop");
        }
    }

    synchronized private void wakeup() {
        notifyAll();
    }

    public void sendMessage(Message message) {
        messagesToSend.add(message);
        wakeup();
    }

    public void shutdown() {
        wakeup();
        stopSending = true;
        this.interrupt();

        for (SocketMessagingThread t : this.peerSocketsMap.values()) {
            t.stop();
        }
    }
}
