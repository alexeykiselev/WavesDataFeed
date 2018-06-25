package com.wavesplatform.datafeed.model

import java.text.DecimalFormat

import com.wavesplatform.datafeed.NodeApiWrapper
import com.wavesplatform.datafeed.settings.WDFSettings
import com.wavesplatform.datafeed.storage.MVStoreDataFeedStorage
import com.wavesplatform.datafeed.utils._
import play.api.libs.json.{JsObject, Json}

import scala.collection.mutable.ArrayBuffer

case class AssetPair(settings: WDFSettings, nodeApi: NodeApiWrapper, amountAsset: String, priceAsset: String, DFDB: MVStoreDataFeedStorage, uetx: UnconfirmedETX) extends Logging {

  private val MaxTrades = 1000
  private val MaxCandles = 1000
  private val MinTimeFrame = 5
  private val DaySize = 1728

  private val MatcherScale = 1e8

  private val trades = new ArrayBuffer[Trade]

  private var startTimestamp = 0L
  private val ohlcv = new ArrayBuffer[Long]

  private lazy val amountAssetIssueTx = nodeApi.get("/transactions/info/" + amountAsset)
  private lazy val priceAssetIssueTx = nodeApi.get("/transactions/info/" + priceAsset)

  private val amountAssetDecimals = if (amountAsset == "WAVES") 8 else (amountAssetIssueTx \ "decimals").as[Int]
  private val amountAssetName = if (amountAsset == "WAVES") "WAVES" else (amountAssetIssueTx \ "name").as[String]
  private val priceAssetDecimals = if (priceAsset == "WAVES") 8 else (priceAssetIssueTx \ "decimals").as[Int]
  private val priceAssetName = if (priceAsset == "WAVES") "WAVES" else (priceAssetIssueTx \ "name").as[String]

  private val amountScale = Math.pow(10, amountAssetDecimals).toLong
  private val diffScale = Math.pow(10, priceAssetDecimals - amountAssetDecimals)

  private val amountFormatter = new DecimalFormat()
  amountFormatter.setMinimumFractionDigits(amountAssetDecimals)
  amountFormatter.setGroupingUsed(false)

  private val priceFormatter = new DecimalFormat()
  priceFormatter.setMinimumFractionDigits(priceAssetDecimals)
  priceFormatter.setGroupingUsed(false)


  private def getAssetSupply(assetId: String): (Long, Long, Long) =
    if (assetId == "WAVES") (1e8.toLong, 1e8.toLong, 1e8.toLong) else {
      val issueTx = nodeApi.get("/transactions/info/" + assetId)
      val assets = (nodeApi.get("/assets/balance/" + (issueTx \ "sender").as[String]) \ "balances").as[List[JsObject]]
      var supply = 0L
      var balance = 0L
      var reissuable = false
      assets.foreach(a => if ((a \ "assetId").as[String] == assetId) {
        supply = (a \ "quantity").as[Long] / Math.pow(10, (a \ "issueTransaction" \ "decimals").as[Int]).toLong
        balance = (a \ "balance").as[Long] / Math.pow(10, (a \ "issueTransaction" \ "decimals").as[Int]).toLong
        reissuable = (a \ "reissuable").as[Boolean]
      })
      (supply, if (reissuable) -1L else supply, supply - balance)
    }

  private val (aTotalSupply, aMaxSupply, aCSupply) = getAssetSupply(amountAsset)
  private val (pTotalSupply, pMaxSupply, pCSupply) = getAssetSupply(priceAsset)

  private val amountAssetTotalSupply = amountFormatter.format(aTotalSupply)
  private val amountAssetMaxSupply = if (aMaxSupply == -1L) "infinite" else amountFormatter.format(aMaxSupply)
  private val amountAssetCirculatingSupply = amountFormatter.format(aCSupply)
  private val priceAssetTotalSupply = amountFormatter.format(pTotalSupply)
  private val priceAssetMaxSupply = if (pMaxSupply == -1L) "infinite" else amountFormatter.format(pMaxSupply)
  private val priceAssetCirculatingSupply = amountFormatter.format(pCSupply)

  private val amountSymbol = keyForValue(settings.symbols, amountAsset) match {
    case Some(s) => s._1
    case None => ""
  }

  private val priceSymbol = keyForValue(settings.symbols, priceAsset) match {
    case Some(s) => s._1
    case None => ""
  }

  val symbol: String = if (amountSymbol != "" && priceSymbol != "") amountSymbol + "/" + priceSymbol else ""

  private def keyForValue(map: Map[String, String], value: String) = map.find({ case (a, b) => b == value })

  private def formatAmount(value: Long): String = amountFormatter.format(value.toDouble / amountScale)

  private def formatPrice(value: BigInt): String = priceFormatter.format(value.toDouble / (diffScale * MatcherScale))

  private def numberOfTrades: Int = DFDB.getTradesMapSize(amountAsset, priceAsset)

  def addTrade(trade: Trade): Unit = {
    if (trades.size == MaxTrades) trades.remove(MaxTrades - 1)
    trade +=: trades
    updateOHLCV(trade)
    DFDB.putTrade(trade)
    val mapSize = DFDB.getCandlesMapSize(amountAsset, priceAsset)
    for (i <- Math.min(mapSize, ohlcv.size / DaySize - 1) until ohlcv.size / DaySize)
      DFDB.putCandles(amountAsset, priceAsset, startTimestamp + i * 86400000L, ohlcv.slice(i * DaySize, i * DaySize + DaySize))
  }

  def loadPair(): Unit = {
    trades ++= DFDB.getLastNTrades(amountAsset, priceAsset, MaxTrades)
    startTimestamp = DFDB.getCandlesKey(amountAsset, priceAsset, 0)
    for (i <- 0 until DFDB.getCandlesMapSize(amountAsset, priceAsset))
      ohlcv ++= DFDB.getCandles(amountAsset, priceAsset, DFDB.getCandlesKey(amountAsset, priceAsset, i))
    log.info("Loaded pair " + amountAsset + "-" + priceAsset + "(" + trades.size + "," + ohlcv.size / DaySize + ")")

  }

  private def updateOHLCV(trade: Trade): Unit = {
    val offset = ohlcvArrayOffset(trade.timestamp, MinTimeFrame)
    if (ohlcv.isEmpty) startTimestamp = (trade.timestamp / 86400000L) * 86400000L
    if (offset >= ohlcv.size) ohlcv ++= ArrayBuffer.tabulate(((offset - ohlcv.size) / DaySize + 1) * DaySize)(_ => 0L)

    if (ohlcv(offset) == 0) ohlcv(offset) = trade.price // open
    ohlcv(offset + 1) = ohlcv(offset + 1).max(trade.price) // high
    ohlcv(offset + 2) = if (ohlcv(offset + 2) > 0 && trade.price > 0) ohlcv(offset + 2).min(trade.price) else ohlcv(offset + 2) | trade.price // low
    ohlcv(offset + 3) = trade.price // close
    if ((ohlcv(offset + 5) + trade.amount) > 0) ohlcv(offset + 4) = ((BigInt(ohlcv(offset + 4)) * ohlcv(offset + 5) + BigInt(trade.price) * trade.amount) / (ohlcv(offset + 5) + trade.amount)).toLong // weighted average
    ohlcv(offset + 5) += trade.amount // volume
  }

  private def getCandle(timestamp: Long, timeframe: Int, uMinTime: Long, uMaxTime: Long, prefix: String): JsObject = {
    val offset = ohlcvArrayOffset(timestamp, 5)
    val aggrSize = timeframe / MinTimeFrame
    var open, high, low, close, volume, average = 0L
    var confirmed = true
    if (timestamp >= startTimestamp && timestamp < (startTimestamp + (ohlcv.size / DaySize) * 86400000L)) {
      for (i <- 0 until aggrSize) {
        val adjOffset = offset + i * 6
        if (adjOffset < (ohlcv.size - 6)) {
          if (open == 0) open = ohlcv(adjOffset)
          high = high.max(ohlcv(adjOffset + 1))
          low = if (low > 0 && ohlcv(adjOffset + 2) > 0) low.min(ohlcv(adjOffset + 2)) else ohlcv(adjOffset + 2) | low
          if (ohlcv(adjOffset + 3) != 0) close = ohlcv(adjOffset + 3)
          if ((volume + ohlcv(adjOffset + 5)) > 0) average = ((BigInt(average) * volume + BigInt(ohlcv(adjOffset + 4)) * ohlcv(adjOffset + 5)) / (volume + ohlcv(adjOffset + 5))).toLong
          volume += ohlcv(adjOffset + 5)
        }

      }
    }

    if (timestamp <= uMaxTime && (timestamp + timeframe * 60000L) >= uMinTime) {
      val uCandle = uetx.get(amountAsset, priceAsset, timestamp, timestamp + timeframe * 60000L)
      if (uCandle.nonEmpty) {
        if (open == 0) open = uCandle.takeRight(1).head.price
        high = high.max(uCandle.maxBy(_.price).price)
        low = if (low > 0 && uCandle.minBy(_.price).price > 0) low.min(uCandle.minBy(_.price).price) else uCandle.minBy(_.price).price | low
        close = uCandle.head.price
        val pVolume = volume
        volume += uCandle.foldLeft(0L)(_ + _.amount)
        //average = average * pVolume +
        confirmed = false
      }
    }

    Json.obj(
      "timestamp" -> timestamp,
      prefix + "open" -> formatPrice(open),
      prefix + "high" -> formatPrice(high),
      prefix + "low" -> formatPrice(low),
      prefix + "close" -> formatPrice(close),
      prefix + "vwap" -> formatPrice(average),
      prefix + "volume" -> formatAmount(volume),
      prefix + "priceVolume" -> formatPrice(BigInt(average) * volume / amountScale),
      "confirmed" -> confirmed
    )

  }

  private def jsonTick(trade: Trade, confirmed: Boolean): JsObject =
    Json.obj(
      "timestamp" -> trade.timestamp,
      "id" -> trade.id,
      "confirmed" -> confirmed,
      "type" -> (if (trade.orderType == 0) "buy" else "sell"),
      "price" -> formatPrice(trade.price),
      "amount" -> formatAmount(trade.amount),
      "buyer" -> trade.buyer,
      "seller" -> trade.seller,
      "matcher" -> trade.matcher
    )

  private def aggregatedTrades(fromTimeStamp: Long, toTimeStamp: Long): List[(Trade, Boolean)] =
    uetx.get(amountAsset, priceAsset, fromTimeStamp, toTimeStamp).map((_, false)) ++ DFDB.getTrades(amountAsset, priceAsset, fromTimeStamp, toTimeStamp, MaxTrades).map((_, true))

  private def aggregatedTradesByAddress(address: String): List[(Trade, Boolean)] =
    uetx.getByAddress(amountAsset, priceAsset, address).map((_, false)) ++ DFDB.getLastNTradesByAddress(amountAsset, priceAsset, address, MaxTrades).map((_, true))

   def getMarket: JsObject =
    get24HView ++
      Json.obj(
        "totalTrades" -> numberOfTrades,
        "firstTradeDay" -> startTimestamp,
        "lastTradeDay" -> (startTimestamp + ((ohlcv.size / DaySize) - 1) * DaySize))


  def getTicker: JsObject =
    get24HView ++ Json.obj("timestamp" -> System.currentTimeMillis())

  def getTradesRange(fromTimeStamp: Long, toTimeStamp: Long): List[JsObject] =
    aggregatedTrades(fromTimeStamp, toTimeStamp).take(MaxTrades)
      .map(trade => jsonTick(trade._1, trade._2))

  def getTradesLimit(ntrades: Int): List[JsObject] =
    (uetx.getAll(amountAsset, priceAsset).map((_, false)) ++ trades.map((_, true)))
      .take(Math.min(ntrades, MaxTrades))
      .map(trade => jsonTick(trade._1, trade._2))

  def getTradesByAddress(address: String, nTrades: Int): List[JsObject] =
    aggregatedTradesByAddress(address)
      .take(Math.min(nTrades, MaxTrades))
      .map(trade => jsonTick(trade._1, trade._2))

  def getCandlesRange(fromTimeStamp: Long, toTimeStamp: Long, timeFrame: Int): List[JsObject] = {
    val adjFrom = (fromTimeStamp / (timeFrame * 60000L)) * (timeFrame * 60000L)
    val adjTo = Math.min(toTimeStamp, fromTimeStamp + MaxCandles * timeFrame * 60000L)
    val nBars = ((adjTo - fromTimeStamp) / (timeFrame * 60000L)).toInt + 1

    val ndays = ohlcv.size / DaySize

    val unconfirmed = uetx.getAll(amountAsset, priceAsset)

    val uMinTime = if (unconfirmed.isEmpty) 0L else unconfirmed.minBy(_.timestamp).timestamp
    val uMaxTime = if (unconfirmed.isEmpty) 0L else unconfirmed.maxBy(_.timestamp).timestamp

    (nBars - 1 to 0 by -1).map(i =>
      getCandle(adjFrom + i * timeFrame * 60000L, timeFrame, uMinTime, uMaxTime, "")).toList
  }

  def getCandlesLimit(timeFrame: Int, limit: Int): List[JsObject] = {
    val toTimeStamp = (System.currentTimeMillis() / (timeFrame * 60000L)) * (timeFrame * 60000L)
    val fromTimeStamp = toTimeStamp - (Math.min(limit, MaxCandles) - 1) * timeFrame * 60000L
    getCandlesRange(fromTimeStamp, toTimeStamp, timeFrame)
  }

  private def get24HView: JsObject = {
    val now = (System.currentTimeMillis / 300000L) * 300000L
    val t1 = now - 287 * 300000L
    Json.obj("symbol" -> symbol,
      "amountAssetID" -> amountAsset,
      "amountAssetName" -> amountAssetName,
      "amountAssetDecimals" -> amountAssetDecimals,
      "amountAssetTotalSupply" -> amountAssetTotalSupply,
      "amountAssetMaxSupply" -> amountAssetMaxSupply,
      "amountAssetCirculatingSupply" -> amountAssetCirculatingSupply,
      "priceAssetID" -> priceAsset,
      "priceAssetName" -> priceAssetName,
      "priceAssetDecimals" -> priceAssetDecimals,
      "priceAssetTotalSupply" -> priceAssetTotalSupply,
      "priceAssetMaxSupply" -> priceAssetMaxSupply,
      "priceAssetCirculatingSupply" -> priceAssetCirculatingSupply) ++
      (getCandle(t1, 1440, 0L, 0L, "24h_") - "timestamp" - "confirmed")
  }

  private def ohlcvArrayOffset(timestamp: Long, timeFrame: Int): Int = {
    val base = if (startTimestamp > 0) startTimestamp else (timestamp / 86400000L) * 86400000L
    val aggrSize = timeFrame / MinTimeFrame
    val offset = 6 * ((timestamp - base) / (timeFrame * 60000L)).toInt * aggrSize
    if (offset > 0) offset else 0
  }

}
