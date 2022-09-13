package flowdistribute;

import java.io.Serializable;

public abstract class OdData implements Serializable {
    private String inId;
    private String outId;

    public OdData(String inId, String outId) {
        this.inId = inId;
        this.outId = outId;
    }

    public String getInId() {
        return inId;
    }

    public String getOutId() {
        return outId;
    }

    public void setInId(String inId) {
        this.inId = inId;
    }

    public void setOutId(String outId) {
        this.outId = outId;
    }

    @Override
    public String toString() {
        return "OdData{" +
                "inId='" + inId + '\'' +
                ", outId='" + outId + '\'' +
                '}';
    }

}
