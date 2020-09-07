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

package uk.gov.hmrc.upscanuploadproxy.services

import org.mockito.scalatest.MockitoSugar
import org.scalatest.EitherValues
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.libs.ws.WSResponse
import play.api.http.Status.{BAD_REQUEST, INTERNAL_SERVER_ERROR, OK, SEE_OTHER}
import uk.gov.hmrc.upscanuploadproxy.services.ProxyService.{FailureResponse, toResultEither}

class ProxyServiceSpec extends AnyWordSpecLike with should.Matchers with MockitoSugar with EitherValues {

  private trait Fixture {
    val response = mock[WSResponse]

    def stubResponse(
      withStatus: Int,
      withHeaders: Option[Map[String, Seq[String]]] = None,
      withBody: String                              = ""): Unit = {
      when(response.status).thenReturn(withStatus)
      withHeaders.foreach(headers => when(response.headers).thenReturn(headers))
      when(response.body).thenReturn(withBody)
    }
  }

  "toResultEither" should {
    "consider a 2xx response a success" in new Fixture {
      stubResponse(withStatus = OK, withHeaders = Some(Map.empty))

      toResultEither(response) should be a 'right
    }

    "consider a 3xx response a success" in new Fixture {
      stubResponse(withStatus = SEE_OTHER, withHeaders = Some(Map.empty))

      toResultEither(response) should be a 'right
    }

    "consider a 4xx response a failure" in new Fixture {
      val errorMessage = "Some Error Message"
      stubResponse(withStatus = BAD_REQUEST, withBody = errorMessage)

      toResultEither(response).left.value shouldBe FailureResponse(statusCode = BAD_REQUEST, body = errorMessage)
    }

    "consider a 5xx response a failure" in new Fixture {
      val errorMessage = "Some Error Message"
      stubResponse(withStatus = INTERNAL_SERVER_ERROR, withBody = errorMessage)

      toResultEither(response).left.value shouldBe FailureResponse(
        statusCode = INTERNAL_SERVER_ERROR,
        body       = errorMessage)
    }
  }
}
