import org.apache.kafka.common.cache.LRUCache

class LRUCacheTest {

}
object LRUCacheTest{
  def main(args: Array[String]): Unit = {
    val pathMathCache = new LRUCache[String,String](2)
    pathMathCache.put("1","2")
    pathMathCache.put("2","3")
    pathMathCache.put("3","4")
    println(pathMathCache)
    println(pathMathCache.get("1").charAt(0))
  }
}
