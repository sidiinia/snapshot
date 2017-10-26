import java.io.Serializable;

public class Packet implements Serializable {

    private final String message;

    private final int port;

    private int money;

    private int type;

    public Packet(int type, String message, int port, int money) {
        this.type = type;
        this.message = message;
        this.port = port;
        this.money = money;
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

    @Override
    public String toString() {
        return String.format("Packet [message=%s, receiving processId=%s, piggyback time=%s]", message, port, money);
    }
}