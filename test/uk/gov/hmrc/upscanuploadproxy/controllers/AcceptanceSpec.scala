/*
 * Copyright 2021 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.upscanuploadproxy.controllers

import org.mockito.scalatest.MockitoSugar
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.WSClient
import uk.gov.hmrc.upscanuploadproxy.UploadUriGenerator
import uk.gov.hmrc.upscanuploadproxy.controllers.utils.WireMockHelper

import scala.concurrent.ExecutionContextExecutor

trait AcceptanceSpec
    extends AnyWordSpec
    with Matchers
    with GuiceOneServerPerSuite
    with MockitoSugar
    with WireMockHelper {

  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
  protected val wsClient: WSClient          = fakeApplication().injector.instanceOf(classOf[WSClient])

  val S3Path = "/s3"

  override def fakeApplication(): Application = {

    val uploadUrlGenerator = new UploadUriGenerator {
      override def uri(bucketName: String): String = wireMockUrl + S3Path
    }

    new GuiceApplicationBuilder()
      .overrides(bind[UploadUriGenerator].toInstance(uploadUrlGenerator))
      /*
       * Disable metrics modules for acceptance tests to avoid:
       *   Error injecting constructor, java.lang.IllegalArgumentException: A metric named jvm.attribute.vendor already exists
       */
      .disable(classOf[com.kenshoo.play.metrics.PlayModule])
      .disable(classOf[uk.gov.hmrc.play.bootstrap.graphite.GraphiteMetricsModule])
      .build()
  }

}
