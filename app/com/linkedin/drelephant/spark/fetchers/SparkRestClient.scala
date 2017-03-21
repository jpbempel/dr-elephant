/*
 * Copyright 2016 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.linkedin.drelephant.spark.fetchers

import java.io.BufferedInputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.zip.{ZipEntry, ZipInputStream}
import java.util.{Calendar, SimpleTimeZone}

import scala.async.Async
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.linkedin.drelephant.spark.data.{SparkLogDerivedData, SparkRestDerivedData}
import com.linkedin.drelephant.spark.fetchers.statusapiv1.{ApplicationInfo, ExecutorSummary, JobData, StageData}
import javax.ws.rs.client.{Client, ClientBuilder, WebTarget}
import javax.ws.rs.core.MediaType

import org.apache.hadoop.fs.Path
import org.apache.log4j.Logger
import org.apache.spark.SparkConf

import scala.collection.mutable

/**
  * A client for getting data from the Spark monitoring REST API, e.g. <https://spark.apache.org/docs/1.4.1/monitoring.html#rest-api>.
  *
  * Jersey classloading seems to be brittle (at least when testing in the console), so some of the implementation is non-lazy
  * or synchronous when needed.
  */
class SparkRestClient(sparkConf: SparkConf) {
  import SparkRestClient._
  import Async.{async, await}

  private val logger: Logger = Logger.getLogger(classOf[SparkRestClient])

  private val client: Client = ClientBuilder.newClient()

  private val historyServerUri: URI = sparkConf.getOption(HISTORY_SERVER_ADDRESS_KEY) match {
    case Some(historyServerAddress) =>
      val baseUri: URI =
        // Latest versions of CDH include http in their history server address configuration.
        // However, it is not recommended by Spark documentation(http://spark.apache.org/docs/latest/running-on-yarn.html)
        if (historyServerAddress.contains(s"http://")) {
          new URI(historyServerAddress)
        } else {
          new URI(s"http://${historyServerAddress}")
        }
      require(baseUri.getPath == "")
      baseUri
    case None =>
      throw new IllegalArgumentException("spark.yarn.historyServer.address not provided; can't use Spark REST API")
  }

  private val apiTarget: WebTarget = client.target(historyServerUri).path(API_V1_MOUNT_PATH)

  def fetchRestData(appId: String)(implicit ec: ExecutionContext): Future[SparkRestDerivedData] = {
    val appTarget = apiTarget.path(s"applications/${appId}")
    logger.info(s"calling REST API at ${appTarget.getUri}")

    val applicationInfo = getApplicationInfo(appTarget)

    // Limit scope of async.
    val lastAttemptId = applicationInfo.attempts.maxBy {_.startTime}.attemptId
    val attemptTarget = lastAttemptId.map(appTarget.path).getOrElse(appTarget)
    async {
      val futureJobDatas = async { getJobDatas(attemptTarget) }
      val futureStageDatas = async { getStageDatas(attemptTarget) }
      val futureExecutorSummaries = async { getExecutorSummaries(attemptTarget) }
      SparkRestDerivedData(
        applicationInfo,
        await(futureJobDatas),
        await(futureStageDatas),
        await(futureExecutorSummaries)
      )
    }
  }

  def fetchLogData(appId: String, attemptId: Option[String])(
      implicit ec: ExecutionContext
  ): Future[Option[SparkLogDerivedData]] = {
    val appTarget = apiTarget.path(s"applications/${appId}")
    logger.info(s"calling REST API at ${appTarget.getUri}")

    val logPrefix = attemptId.map(id => s"${appId}_$id").getOrElse(appId)
    async {
      resource.managed { getApplicationLogs(appTarget) }.acquireAndGet { zis =>
        var entry: ZipEntry = null
        do {
          zis.closeEntry()
          entry = zis.getNextEntry
        } while (!(entry == null || entry.getName.startsWith(logPrefix)))

        if (entry == null) {
          logger.warn(
            s"failed to resolve log starting with $logPrefix for ${appTarget.getUri}")
          None
        } else {
          val codec = SparkLogClient.compressionCodecForLogName(sparkConf, entry.getName)
          Some(SparkLogClient.findDerivedData(
            codec.map { _.compressedInputStream(zis) }.getOrElse(zis)))
        }
      }
    }
  }

  private def getApplicationInfo(appTarget: WebTarget): ApplicationInfo = {
    try {
      get(appTarget, SparkRestObjectMapper.readValue[ApplicationInfo])
    } catch {
      case NonFatal(e) => {
        logger.error(s"error reading applicationInfo ${appTarget.getUri}", e)
        throw e
      }
    }
  }

  private def getApplicationLogs(appTarget: WebTarget): ZipInputStream = {
    val target = appTarget.path("logs")
    try {
      val is = target.request(MediaType.APPLICATION_OCTET_STREAM)
          .get(classOf[java.io.InputStream])
      new ZipInputStream(new BufferedInputStream(is))
    } catch {
      case NonFatal(e) => {
        logger.error(s"error reading logs ${target.getUri}", e)
        throw e
      }
    }
  }

  private def getJobDatas(attemptTarget: WebTarget): Seq[JobData] = {
    val target = attemptTarget.path("jobs")
    try {
      get(target, SparkRestObjectMapper.readValue[Seq[JobData]])
    } catch {
      case NonFatal(e) => {
        logger.error(s"error reading jobData ${target.getUri}", e)
        throw e
      }
    }
  }

  private def getStageDatas(attemptTarget: WebTarget): Seq[StageData] = {
    val target = attemptTarget.path("stages")
    try {
      get(target, SparkRestObjectMapper.readValue[Seq[StageData]])
    } catch {
      case NonFatal(e) => {
        logger.error(s"error reading stageData ${target.getUri}", e)
        throw e
      }
    }
  }

  private def getExecutorSummaries(attemptTarget: WebTarget): Seq[ExecutorSummary] = {
    val target = attemptTarget.path("executors")
    try {
      get(target, SparkRestObjectMapper.readValue[Seq[ExecutorSummary]])
    } catch {
      case NonFatal(e) => {
        logger.error(s"error reading executorSummary ${target.getUri}", e)
        throw e
      }
    }
  }
}

object SparkRestClient {
  val HISTORY_SERVER_ADDRESS_KEY = "spark.yarn.historyServer.address"
  val API_V1_MOUNT_PATH = "api/v1"

  val SparkRestObjectMapper = {
    val dateFormat = {
      val iso8601 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'GMT'")
      val cal = Calendar.getInstance(new SimpleTimeZone(0, "GMT"))
      iso8601.setCalendar(cal)
      iso8601
    }

    val objectMapper = new ObjectMapper() with ScalaObjectMapper
    objectMapper.setDateFormat(dateFormat)
    objectMapper.registerModule(DefaultScalaModule)
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    objectMapper
  }

  def get[T](webTarget: WebTarget, converter: String => T): T =
    converter(webTarget.request(MediaType.APPLICATION_JSON).get(classOf[String]))
}
