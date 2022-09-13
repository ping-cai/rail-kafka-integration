package control

import java.{lang, util}

import calculate.BaseCalculate
import costcompute.{MinGeneralizedCost, TimeIntervalStationFlow, TimeIntervalTraffic, TimeIntervalTransferFlow}
import distribution.{DistributionResult, StationWithType, TransferWithDirection}
import domain.Section
import flowdistribute.OdWithTime
import org.apache.hadoop.yarn.util.LRUCacheHashMap
import utils.TimeKey

trait Control extends Serializable {

  def startup(controlInfo: ControlInfo)

}

object Control {
  def createLRUDistributionResult(): DistributionResult = {
    val timeSectionFlow = new LRUCacheHashMap[TimeKey, util.Map[Section, lang.Double]](200, false)
    val timeIntervalTraffic = new TimeIntervalTraffic(timeSectionFlow)
    val timeStationFlow = new LRUCacheHashMap[TimeKey, util.Map[StationWithType, lang.Double]](200, false)
    val timeIntervalStationFlow = new TimeIntervalStationFlow(timeStationFlow)
    val timeTransferFlow = new LRUCacheHashMap[TimeKey, util.Map[TransferWithDirection, lang.Double]](200, false)
    val timeIntervalTransferFlow = new TimeIntervalTransferFlow(timeTransferFlow)
    val distributionResult = new DistributionResult(timeIntervalTraffic, timeIntervalStationFlow, timeIntervalTransferFlow)
    distributionResult
  }

  def createDistributionResult(): DistributionResult = {
    val timeSectionFlow = new util.HashMap[TimeKey, util.Map[Section, lang.Double]]()
    val timeIntervalTraffic = new TimeIntervalTraffic(timeSectionFlow)
    val timeStationFlow = new util.HashMap[TimeKey, util.Map[StationWithType, lang.Double]]()
    val timeIntervalStationFlow = new TimeIntervalStationFlow(timeStationFlow)
    val timeTransferFlow = new util.HashMap[TimeKey, util.Map[TransferWithDirection, lang.Double]]()
    val timeIntervalTransferFlow = new TimeIntervalTransferFlow(timeTransferFlow)
    val distributionResult = new DistributionResult(timeIntervalTraffic, timeIntervalStationFlow, timeIntervalTransferFlow)
    distributionResult
  }

  def tryCost(baseCalculate: BaseCalculate, kspNumber: Int, odWithTime: OdWithTime) = {
    val legalPath = baseCalculate.getLegalPathList(kspNumber, odWithTime)
    val staticCost = baseCalculate.getStaticCost(legalPath)
    val minGeneralizedCost = new MinGeneralizedCost().compose(staticCost)
    //    合法的路径集合，静态广义费用，最小广义费用
    (legalPath, staticCost, minGeneralizedCost)
  }
}

