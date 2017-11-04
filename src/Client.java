import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.Semaphore;

import static java.lang.Thread.sleep;

public class Client {
    static int port;
    static int[] portNums;
    static volatile int localBalance = 1000;

    static volatile List<Socket> incomingSockets = new ArrayList<>();
    static volatile List<Socket> outgoingSockets = new ArrayList<>();

    // recorded states
    static volatile int localState;
    static volatile Map<Integer, Map<Integer, Queue<Packet>>> queueMap = new HashMap<>();
    //                   |              |       |
    //                  marker #      port     queue


    // 0 --> no marker, 1 --> first marker, 2 --> subsequent markers
    static volatile Map<Integer, Map<Integer, Integer>> channelState = new HashMap<>();
    //                   |              |       |
    //                  marker #      port     state

    static int TRANSACTION = 1;
    static int MARKER = 2;

    public static void main(String[] args) throws IOException, ClassNotFoundException {
        if (args.length < 1) {
            System.out.println("please specify port num");
            System.exit(0);
        }

        port = Integer.parseInt(args[0]);

        // read in config file which contains port num
        Scanner sc = new Scanner(new File("config.txt"));
        String line = sc.nextLine();
        sc.close();
        String[] ports = line.split(" ");
        portNums = new int[ports.length];
        for (int i = 0; i < ports.length; i++) {
            portNums[i] = Integer.parseInt(ports[i]);
        }

        CreateServerSocket ss = new CreateServerSocket(port);
        ss.start();

        // check if server exists, if exists, connect clients, else wait
        for (int i = 0; i < portNums.length; i++) {
            if (portNums[i] != port) {
                while (!serverListening("127.0.0.1", portNums[i])) {}
                Socket s = new Socket("127.0.0.1", portNums[i]);
                outgoingSockets.add(s);
            }
        }

        // initialize channel state
        for (int i = 0; i < portNums.length; i++) {
            Map<Integer, Integer> temp = new HashMap<>();
            for (int j = 0; j < portNums.length; j++) {
                //if (i != j) {
                    temp.put(portNums[j], 0);
                //}
            }
            channelState.put(portNums[i], temp);
        }

        // wait for all the clients to come in
        while (incomingSockets.size() != 2*(portNums.length-1)) {}
        for (int i = 0; i < incomingSockets.size(); i++) {
            if (incomingSockets.get(i).isClosed()) {
                incomingSockets.remove(incomingSockets.get(i));
            }
        }

        //read
        for (int i = 0; i < incomingSockets.size(); i++) {
            ReadThread r1 = new ReadThread(incomingSockets.get(i));
            Thread t = new Thread(r1);
            t.start();
        }

        SendMoneyThread sm = new SendMoneyThread(outgoingSockets, port, 0.5);
        Thread smt = new Thread(sm);
        smt.start();

        // write
        String clientMessage = "";
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));

        try {
            while (!clientMessage.equals("quit")) {
                clientMessage = br.readLine();
                if (clientMessage.equals("snapshot")) {
                    // start snapshot
                    System.out.println("Client " + port + " initiated snapshot");
                    localState = localBalance;

                    System.out.println("start recording all incoming channels");
                    Map<Integer, Integer> temp = channelState.get(port);
                    for (int i = 0; i < portNums.length; i++) {
                        //if (portNums[i] != port) {
                            temp.put(portNums[i], 1);
                        //}
                    }
                    channelState.put(port, temp);

                    sendMarker();
                }
                else if (!clientMessage.equals("")) {
                    //write(clientMessage);
                }
            }
        } catch (IOException e) {

        }
    }

    public static boolean serverListening(String host, int port)
    {
        Socket s = null;
        try
        {
            s = new Socket(host, port);
            return true;
        }
        catch (Exception e)
        {
            return false;
        }
        finally
        {
            if(s != null)
                try {s.close();}
                catch(Exception e){}
        }
    }

    public static void sendMarker() {
        //System.out.println("size: " + outgoingSockets.size());
        for (int i = 0; i < outgoingSockets.size(); i++) {
            Socket clientSocket = outgoingSockets.get(i);
            Packet packet = new Packet(MARKER, "Sent Marker from Client " + port, port, 0, port);

            try {
                ObjectOutputStream outStream = new ObjectOutputStream(clientSocket.getOutputStream());
                outStream.writeObject(packet);
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.println(packet.getMessage());
            queueMap.put(port, new HashMap<>());
        }
    }
}

class ReadThread implements Runnable {
    Socket clientSocket;
    static int TRANSACTION = 1;
    static Semaphore semaphore = new Semaphore(1);
    public ReadThread(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    @Override
    public void run() {
        while (true) {
            Packet packet;
            ObjectInputStream inStream;
            try {
                //System.out.println("reading from " + clientSocket);
                inStream = new ObjectInputStream(clientSocket.getInputStream());
                packet = (Packet) inStream.readObject();
                try {
                    sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                // handle money transaction
                if (packet.getType() == TRANSACTION) {
                    //System.out.println("A TRANSACTION PACKET");
                    // recording
                    // System.out.println("client state" + Client.channelState.get(packet.getSender()));
                    for (Integer i : Client.channelState.keySet()) {
                        if (packet.getSender() != i && Client.channelState.get(i).get(packet.getSender()) == 1) {
                            System.out.println("recording..............");
                            for (Integer initiatorPort : Client.queueMap.keySet()) {
                                Map<Integer, Queue<Packet>> mq = Client.queueMap.get(initiatorPort);
                                for (Integer senderPort : mq.keySet()) {
                                    Queue<Packet> q = mq.get(senderPort);
                                    q.add(packet);
                                    mq.put(senderPort, q);
                                }
                                Client.queueMap.put(initiatorPort, mq);
                            }
                        }
                    }
                    semaphore.acquire();
                    Client.localBalance += packet.getMoney();
                    System.out.println("received $" + packet.getMoney() + " from Client " + packet.getSender() + ". local balance is " + Client.localBalance);
                    semaphore.release();
                }

                // handle marker
                else {
                    //System.out.println("A MARKER PACKET");

                    // initiator
                    if (packet.getPort() == Client.port) {
                        System.out.println("Initiator received a marker from " + packet.getSender());

                        semaphore.acquire();
                        Map<Integer, Integer> initiatorChannelState = Client.channelState.get(packet.getPort());
                        initiatorChannelState.put(packet.getSender(), 2);
                        Client.channelState.put(packet.getPort(), initiatorChannelState);
                        semaphore.release();
                        //System.out.println("size: "+Client.channelState.size());
                        boolean finished = true;
                        initiatorChannelState = Client.channelState.get(packet.getPort());
                        for (Map.Entry<Integer, Integer> entry : initiatorChannelState.entrySet()) {
                            if (entry.getKey() != Client.port) {
                                //System.out.println("channel state is " + entry.getValue());
                                finished = finished & (entry.getValue() == 2);
                            }
                        }
                        if (finished) {
                            // send global states to initiator
                            System.out.println("(Initiator) Client " + Client.port + " finished snapshot");
                            System.out.println("\nGlobal state is: ");
                            printString(Client.queueMap);
                        }
                    }
                    // not initiator
                    else {
                        Map<Integer, Integer> initiatorChannelState = Client.channelState.get(packet.getPort());
                        if (initiatorChannelState.get(packet.getPort()) == 0) {
                            System.out.println("receive first marker from port " + packet.getSender() + ", start recording");
                            Client.queueMap.put(packet.getPort(), new HashMap<Integer, Queue<Packet>>());
                            // receive first marker, start recording state of this channel
                            semaphore.acquire();
                            for (int i = 0; i < Client.portNums.length; i++) {
                                //if (Client.portNums[i] != packet.getPort() && Client.portNums[i] != Client.port) {
                                    initiatorChannelState.put(Client.portNums[i], 1);
                                //}
                            }
                            Client.channelState.put(packet.getPort(), initiatorChannelState);
                            Client.localState = Client.localBalance;
                            semaphore.release();

                            System.out.println("(non-initiator) Client " + Client.port + " sends marker to everybody");
                            packet.setSender(Client.port);
                            for (int i = 0; i < Client.outgoingSockets.size(); i++) {
                                Socket clientSocket = Client.outgoingSockets.get(i);
                                try {
                                    ObjectOutputStream outStream = new ObjectOutputStream(clientSocket.getOutputStream());
                                    outStream.writeObject(packet);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        } else if (initiatorChannelState.get(packet.getPort()) == 1) {
                            System.out.println("received a marker from port " + packet.getSender());
                            System.out.println("stop recording channel from port " + packet.getSender());
                            semaphore.acquire();
                            initiatorChannelState.put(Client.port, 2);
                            initiatorChannelState.put(packet.getSender(), 2);
                            Client.channelState.put(packet.getPort(), initiatorChannelState);
                            semaphore.release();
                            boolean finished = true;
                            for (Map.Entry<Integer, Integer> entry : initiatorChannelState.entrySet()) {
                                if (entry.getKey() != packet.getPort()) {
                                    System.out.println(entry.getKey() + " channel state is " + entry.getValue());
                                    finished = finished & (entry.getValue() == 2);
                                }
                            }
                            if (finished) {
                                // send global states to initiator
                                System.out.println("(Non-initiator) Client " + Client.port + " finished snapshot");
                                System.out.println("\nGlobal state is: ");
                                printString(Client.queueMap);
                            }
                        }
                    }
                }
            } catch (EOFException e) {
                //System.out.println("here");
            } catch (IOException e) {
                e.printStackTrace();
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void printString(Map<Integer, Map<Integer, Queue<Packet>>> map) {
        for (Map.Entry<Integer, Map<Integer, Queue<Packet>>> m1 : map.entrySet()) {
            Map<Integer, Queue<Packet>> mq = m1.getValue();
            System.out.println("    For initiator marker " + m1.getKey() + ":");
            for (Map.Entry<Integer, Queue<Packet>> m2 : mq.entrySet()) {
                System.out.println("        on port " + m2.getKey() + ": " + m2.getValue());
            }
        }
        System.out.println();
    }
}

class SendMoneyThread implements Runnable {

    List<Socket> outgoingSockets;
    int port;
    double pos;

    static int TRANSACTION = 1;
    static int MARKER = 2;
    static Semaphore semaphore = new Semaphore(1);

    public SendMoneyThread(List<Socket> outgoingSockets, int port, double pos) {
        this.outgoingSockets = outgoingSockets;
        this.port = port;
        this.pos = pos;
    }

    @Override
    public void run() {
        while (true) {
            try {
                sleep(10000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            for (int i = 0; i < outgoingSockets.size(); i++) {
                Socket clientSocket = outgoingSockets.get(i);
                int money = (int) (Math.random() * 50 + 1);

                Packet packet = new Packet(TRANSACTION, "Client " + port + " sent $" + money, port, money, port);
                Random rand = new Random();
                int value = rand.nextInt(100);
                if (value < 100 * pos) {
                    System.out.println("sending money $" + packet.getMoney() + " to client " + clientSocket.getPort());
                    ObjectOutputStream outStream = null;
                    try {
                        outStream = new ObjectOutputStream(clientSocket.getOutputStream());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    /*try {
                        sleep(5000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }*/
                    try {
                        outStream.writeObject(packet);
                        semaphore.acquire();
                        Client.localBalance -= money;
                        System.out.println("local balance is " + Client.localBalance);
                        semaphore.release();
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }
}
