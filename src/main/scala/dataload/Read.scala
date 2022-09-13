package dataload

import java.util.Properties

import conf.DynamicConf

trait Read[T] {
  val url: String = DynamicConf.localhostUrl
  val prop: Properties = new java.util.Properties() {
    put("user", DynamicConf.localhostUser)
    put("password", DynamicConf.localhostPassword)
  }

  def read(tableName: String): T
}
