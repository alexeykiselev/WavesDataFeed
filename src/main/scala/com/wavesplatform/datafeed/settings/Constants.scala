package com.wavesplatform.datafeed.settings

import com.typesafe.config.ConfigFactory
import com.wavesplatform.datafeed.utils.Logging

object Constants extends Logging {
  private val appConf = ConfigFactory.load().getConfig("app")

  val Application: String = appConf.getString("application")
  val Product: String = appConf.getString("product")
  val Version: String = appConf.getString("version")

  val UnitsInWave = 100000000L
  val TotalWaves = 100000000L

}
