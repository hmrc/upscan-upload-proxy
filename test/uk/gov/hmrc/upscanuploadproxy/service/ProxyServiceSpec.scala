/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.upscanuploadproxy.service

import org.mockito.Mockito.when
import org.scalatest.EitherValues
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import play.api.http.Status.{BAD_REQUEST, INTERNAL_SERVER_ERROR, OK, SEE_OTHER}
import play.api.libs.ws.WSResponse

class ProxyServiceSpec extends AnyWordSpecLike with should.Matchers with MockitoSugar with EitherValues {
  import ProxyService.{FailureResponse, toResultEither}
  import ProxyServiceSpec._

  private trait Fixture {
    val response = mock[WSResponse]

    def stubResponse(withStatus: Int,
                     withHeaders: Map[String, Seq[String]] = Map.empty,
                     withBody: String = ""): Unit = {
      when(response.status).thenReturn(withStatus)
      when(response.headers).thenReturn(withHeaders)
      when(response.body).thenReturn(withBody)
    }
  }

  "toResultEither" should {
    "consider a 2xx response a success" in new Fixture {
      stubResponse(withStatus = OK)

      toResultEither(response) should be a Symbol("right")
    }

    "consider a 3xx response a success" in new Fixture {
      stubResponse(withStatus = SEE_OTHER)

      toResultEither(response) should be a Symbol("right")
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

    "retain all response headers on success" in new Fixture {
      stubResponse(withStatus = SEE_OTHER, withHeaders = Map("A" -> Seq("1"), "B" -> Seq("2")))

      val resultHeaders = toResultEither(response).value.header.headers

      resultHeaders should contain theSameElementsAs Seq("A" -> "1", "B" -> "2")
    }

    "retain only CORS and custom Amazon response headers on failure" in new Fixture {
      // see https://fetch.spec.whatwg.org/#http-responses
      val corsHeaders = Map(
        "Access-Control-Allow-Origin" -> Seq("https://www.development.tax.service.gov.uk"),
        "Access-Control-Allow-Credentials" -> Seq("true"),
        "Access-Control-Allow-Methods" -> Seq("POST"),
        "Access-Control-Allow-Headers" -> Seq("Content-Type"),
        "Access-Control-Max-Age" -> Seq("3000"),
        "Access-Control-Expose-Headers" -> Seq("Content-Security-Policy")
      )
      val amzHeaders = Map(
        "x-amz-id-2" -> Seq("some-x-amz-id-2-value"),
        "x-amz-expiration" -> Seq("some-x-amz-expiration-value"),
        "x-amz-request-id" -> Seq("some-x-amz-request-id-value"),
        "x-amz-version-id" -> Seq("some-x-amz-version-id-value"),
        "x-amz-server-side-encryption" -> Seq("some-x-amz-server-side-encryption-value")
      )
      val otherHeaders = Map(
        "A" -> Seq("1"),
        "B" -> Seq("2")
      )

      stubResponse(withStatus  = BAD_REQUEST, withBody = "Some Error Message",
        withHeaders = corsHeaders ++ amzHeaders ++ otherHeaders)

      val result = toResultEither(response).left.value

      result.headers should contain theSameElementsAs toTuples(corsHeaders ++ amzHeaders)
      result.headers.map(_._1) should contain.noneOf("A", "B")
    }
  }
}

private object ProxyServiceSpec {
  def toTuples(mapping: Map[String, Seq[String]]): Seq[(String, String)] =
    mapping.toSeq.flatMap { case (key, values) => values.map(key -> _) }
}
