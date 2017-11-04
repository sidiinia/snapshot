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
    static volatile Map<Integer, Queue<Packet>> queueMap = new HashMap<>();

    // 0 --> no marker, 1 --> first marker, 2 --> subsequent markers
    static volatile Map<Integer, Integer> channelState = new HashMap<>();

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
                channelState.put(portNums[i], 0); // initialize channel state map to 0
                while (!serverListening("127.0.0.1", portNums[i])) {}
                Socket s = new Socket("127.0.0.1", portNums[i]);
                outgoingSockets.add(s);
            }
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

        SendMoneyThread sm = new SendMoneyThread(outgoingSockets, port, 1);
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
                    sendMarker();

                    System.out.println("start recording all incoming channels");
                    for (int i = 0; i < portNums.length; i++) {
                        if (portNums[i] != port) {
                            channelState.put(portNums[i], 1);
                        }
                    }
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
            System.out.println(packet.getMessage());
            try {
                ObjectOutputStream outStream = new ObjectOutputStream(clientSocket.getOutputStream());
                outStream.writeObject(packet);
            } catch (IOException e) {
                e.printStackTrace();
            }

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

                    // recording
                    if (Client.channelState.get(packet.getPort()) == 1) {
                        Client.queueMap.getOrDefault(packet.getPort(), new LinkedList<>()).add(packet);
                    }
                    semaphore.acquire();
                    Client.localBalance += packet.getMoney();
                    System.out.println("local balance is " + Client.localBalance);
                    semaphore.release();
                }
                // handle marker
                else {
                    // initiator
                    if (packet.getPort() == Client.port) {
                        System.out.println("Initiator received a marker");
                        if (Client.channelState.get(packet.getSender()) == 1) {
                            Client.channelState.put(packet.getSender(), 2);
                            System.out.println("size: "+Client.channelState.size());
                            boolean finished = true;
                            for (Map.Entry<Integer, Integer> entry : Client.channelState.entrySet()) {
                                if (entry.getKey() != Client.port) {
                                    System.out.println("channel state is " + entry.getValue());
                                    finished = finished & (entry.getValue() == 2);
                                }
                            }
                            if (finished) {
                                // send global states to initiator
                                System.out.println("(Initiator) Client " + Client.port + " finished snapshot");
                            }
                        } else {
                            System.out.println("something went wrong");
                        }
                    }
                    // not initiator
                    else {
                        if (Client.channelState.get(packet.getPort()) == 0) {
                            System.out.println("receive first marker, start recording");
                            Client.queueMap.put(packet.getPort(), new LinkedList<Packet>());
                            // receive first marker, start recording state of this channel
                            Client.channelState.put(packet.getPort(), 1);
                            Client.localState = Client.localBalance;

                            System.out.println("(non-initiator) Client " + Client.port + " sends marker to everybody");
                            for (int i = 0; i < Client.outgoingSockets.size(); i++) {
                                Socket clientSocket = Client.outgoingSockets.get(i);
                                try {
                                    ObjectOutputStream outStream = new ObjectOutputStream(clientSocket.getOutputStream());
                                    outStream.writeObject(packet);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        } else if (Client.channelState.get(packet.getPort()) == 1) {
                            System.out.println("stop recording channel from port " + packet.getPort());
                            Client.channelState.put(packet.getPort(), 2);
                            boolean finished = true;
                            for (Integer i : Client.channelState.values()) {
                                finished = finished & (i == 2);
                            }
                            if (finished) {
                                // send global states to initiator
                                System.out.println("Client " + Client.port + " finished snapshot");
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
