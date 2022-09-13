package distribution;

import java.io.Serializable;

public class DistributionWithTemp implements Serializable {
    private DistributionResult finalResult;
    private DistributionResult tempResult;

    public DistributionWithTemp(DistributionResult finalResult, DistributionResult tempResult) {
        this.finalResult = finalResult;
        this.tempResult = tempResult;
    }
    @Override
    public String toString() {
        return "DistributionWithTemp{" +
                "finalResult=" + finalResult +
                ", tempResult=" + tempResult +
                '}';
    }
}
