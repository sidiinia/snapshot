import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Scanner;

import static java.lang.Thread.sleep;

public class Client {
    static int port;
    static int[] portNums;
    static volatile int localBalance = 1000;
    static volatile List<Socket> readSockets = new ArrayList<>();
    static volatile List<Socket> writeSockets = new ArrayList<>();

    static int TRANSACTION = 1;
    static int MARKER = 2;

    public static void main(String[] args) throws IOException, ClassNotFoundException {
        if (args.length < 1) {
            System.out.println("please specify port num");
            System.exit(0);
        }

        port = Integer.parseInt(args[0]);

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

        for (int i = 0; i < portNums.length; i++) {
            if (portNums[i] != port) {
                while (!serverListening("127.0.0.1", portNums[i])) {}
                Socket s = new Socket("127.0.0.1", portNums[i]);
                writeSockets.add(s);
            }
        }

        while (readSockets.size() != 2*(portNums.length-1)) {}
        //System.out.println(writeSockets);
        //System.out.println(readSockets);

        //read
        for (int i = 0; i < readSockets.size(); i++) {
            ReadThread r1 = new ReadThread(readSockets.get(i));
            Thread t = new Thread(r1);
            t.start();
        }

        SendMoneyThread sm = new SendMoneyThread(writeSockets, port, 0.2);
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
}

class ReadThread implements Runnable {
    Socket clientSocket;
    public ReadThread(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    @Override
    public void run() {
        while (true) {
            Packet packet = null;
            ObjectInputStream inStream;
            try {
                //System.out.println("reading from " + clientSocket);
                inStream = new ObjectInputStream(clientSocket.getInputStream());
                packet = (Packet) inStream.readObject();
            } catch (EOFException e) {
                //System.out.println("here");
            } catch (IOException e) {
                e.printStackTrace();
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
            if (packet != null) {
                System.out.println(packet.getMessage());
            }
        }
    }
}

class SendMoneyThread implements Runnable {

    List<Socket> writeSockets;
    int port;
    double pos;

    static int TRANSACTION = 1;
    static int MARKER = 2;

    public SendMoneyThread(List<Socket> writeSockets, int port, double pos) {
        this.writeSockets = writeSockets;
        this.port = port;
        this.pos = pos;
    }

    @Override
    public void run() {
        while (true) {
            for (int i = 0; i < writeSockets.size(); i++) {
                Socket clientSocket = writeSockets.get(i);
                int money = (int) (Math.random() * 50 + 1);
                Packet packet = new Packet(TRANSACTION, "Client " + port + " sent $" + money, port, money);
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
                    try {
                        sleep(5000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    try {
                        outStream.writeObject(packet);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }
}
