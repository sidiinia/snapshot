import java.io.Serializable;

public class Packet implements Serializable {

    private final String message;

    private final int processId;

    private int numOfLikes;

    private int type;

    /**
     * Piggyback time from sender process.
     */
    private final int time;

    public Packet(int type, String message, int processId, int time, int numOfLikes) {
        this.type = type;
        this.message = message;
        this.processId = processId;
        this.time = time;
        this.numOfLikes = numOfLikes;
    }

    public int getType() {return type; }

    public String getMessage() {
        return message;
    }

    public int getProcessId() {
        return processId;
    }

    public int getTime() {
        return time;
    }

    public int getNumOfLikes() {
        return numOfLikes;
    }

    @Override
    public String toString() {
        return String.format("Packet [message=%s, receiving processId=%s, piggyback time=%s]", message, processId, time);
    }
}