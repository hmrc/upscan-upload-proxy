/*
 * Copyright 2020 HM Revenue & Customs
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

import org.scalatest.BeforeAndAfterEach
import org.scalatest.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.WSClient
import uk.gov.hmrc.upscanuploadproxy.UploadUriGenerator
import uk.gov.hmrc.upscanuploadproxy.controllers.utils.WireMockHelper

import scala.concurrent.ExecutionContextExecutor

trait AcceptanceSpec
    extends PlaySpec
    with GuiceOneServerPerSuite
    with MockitoSugar
    with BeforeAndAfterEach
    with WireMockHelper {

  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
  protected val wsClient: WSClient          = fakeApplication().injector.instanceOf(classOf[WSClient])

  override def beforeEach(): Unit = {}

  override def fakeApplication(): Application = {

    val uploadUrlGenerator = new UploadUriGenerator {
      override def uri(bucketName: String): String = s"$wireMockUrl/s3"
    }

    new GuiceApplicationBuilder()
      .overrides(bind[UploadUriGenerator].toInstance(uploadUrlGenerator))
      .build()
  }

}
