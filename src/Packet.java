import java.io.Serializable;

public class Packet implements Serializable {

    private final String message;

    private final int port;

    private int money;

    private int type;

    private int sender;

    public Packet(int type, String message, int port, int money, int sender) {
        this.type = type;
        this.message = message;
        this.port = port;
        this.money = money;
        this.sender = sender;
    }

    public int getType() {return type; }

    public String getMessage() {
        return message;
    }

    public int getPort() {
        return port;
    }

    public int getMoney() {
        return money;
    }

    public int getSender() { return sender; }
    public void setSender(int sender) { this.sender = sender; }

    @Override
    public String toString() {
        return String.format("Packet [message=%s, receiving processId=%s, piggyback time=%s]", message, port, money);
    }
}