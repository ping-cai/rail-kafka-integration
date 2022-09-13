package costcompute.optimize

import java.sql.Timestamp

import kspcalculation.Path
import model.EdgeCongestion

import scala.collection.mutable

class PathCongestion(edgeCongestion: mutable.HashMap[EdgeCongestion, Double]) {
  /**
    * 路径拥挤度
    *
    * @param inTime 路径开始时间
    * @param path   路径
    * @return 路径拥挤度
    */
  def getPathCongestion(inTime: Timestamp, path: Path): (Path, Double) = {
    (path, 1)
  }
}
