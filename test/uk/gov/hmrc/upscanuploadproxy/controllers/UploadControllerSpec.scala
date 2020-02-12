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

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.http.{HttpHeader, HttpHeaders}
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import com.sun.org.apache.xml.internal.serialize.LineSeparator
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Span}
import play.api.libs.ws.WSResponse
import uk.gov.hmrc.integration.UrlHelper.-/

import scala.io.Source

class UploadControllerSpec extends AcceptanceSpec with ScalaFutures {

  private def resource(path: String): String = s"http://localhost:$port/${-/(path)}"

  private def makeRequest(body: String): WSResponse =
    wsClient
      .url(resource("v1/uploads/bucketName"))
      .withHttpHeaders(
        "Content-Type" -> "multipart/form-data; boundary=--------------------------946347039423050176633444")
      .withFollowRedirects(false)
      .post(body)
      .futureValue

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(scaled(Span(2000, Millis)), scaled(Span(50, Millis)))

  "testHandleRequest" must {

    "proxy response on S3 2xx" in {
      stubForMultipart("/s3", 204)

      val postBody = readResource("/simple-request", LineSeparator.Windows)
      val response = makeRequest(postBody)

      response.status mustBe 204
    }

    "proxy response on S3 3xx" in {

      val locationHeader = new HttpHeader("Location", "https://www.hmrc.gov.uk?a=a&b=b")
      stubForMultipart(url = "/s3", status = 303, headers = new HttpHeaders(locationHeader))

      val postBody = readResource("/simple-request", LineSeparator.Windows)
      val response = makeRequest(postBody)

      response.status mustBe 303
      response.body mustBe ""
      response.header("Location") mustBe Some("https://www.hmrc.gov.uk?a=a&b=b")
    }

    "redirect on S3 4xx" in {
      stubForMultipart("/s3", 400, "<Error><Message>failure</Message></Error>")

      val postBody = readResource("/simple-request", LineSeparator.Windows)
      val response = makeRequest(postBody)

      response.status mustBe 303
      response.body mustBe ""
      response.header("Location") mustBe Some("https://www.amazon.co.uk?errorMessage=failure")
    }

    "redirect - without query parameter on S3 4xx when response xml can not be parsed" in {
      stubForMultipart("/s3", 404, "successful")

      val postBody = readResource("/simple-request", LineSeparator.Windows)
      val response = makeRequest(postBody)

      response.status mustBe 303
      response.body mustBe ""
      response.header("Location") mustBe Some("https://www.amazon.co.uk")
    }

    "redirect on S3 5xx" in {

      stubForMultipart("/s3", 500, "<Error><Message>internal server error</Message></Error>")

      val postBody = readResource("/simple-request", LineSeparator.Windows)
      val response = makeRequest(postBody)

      response.status mustBe 303
      response.body mustBe ""
      response.header("Location") mustBe Some("https://www.amazon.co.uk?errorMessage=internal server error")
    }

    "return json error response on missing destination path parameter" in {

      val postBody = readResource("/simple-request", LineSeparator.Windows)
      val response = wsClient
        .url(resource("v1/uploads"))
        .withHttpHeaders(
          "Content-Type" -> "multipart/form-data; boundary=--------------------------946347039423050176633444")
        .withFollowRedirects(false)
        .post(postBody)
        .futureValue

      response.status mustBe 404
      response.body mustBe "{\"message\":\"Path '/v1/uploads' not found.\"}"
      response.header("Content-Type") mustBe Some("application/json")
    }

    "return json error response on missing content type" in {

      val postBody = readResource("/simple-request", LineSeparator.Windows)

      val response = wsClient
        .url(resource("v1/uploads/bucketName"))
        .withFollowRedirects(false)
        .post(postBody)
        .futureValue

      response.status mustBe 400
      response.body mustBe "{\"message\":\"Bad request: Missing boundary header\"}"
      response.header("Content-Type") mustBe Some("application/json")
    }

    "return json error response when error_action_redirect is missing" in {

      val postBody = readResource("/request-missing-error-action-redirect", LineSeparator.Windows)
      val response = makeRequest(postBody)

      response.status mustBe 400
      response.body mustBe "{\"message\":\"Could not find error_action_redirect field in request\"}"
      response.header("Content-Type") mustBe Some("application/json")
    }

    "return json error response when request can not be parsed" in {

      val response = makeRequest("can not be parsed\n")

      response.status mustBe 400
      response.body mustBe "{\"message\":\"Bad request: Unexpected end of input\"}"
      response.header("Content-Type") mustBe Some("application/json")
    }
  }

  private def readResource(resourcePath: String, lineSeparator: String = ""): String = {

    val url    = getClass.getResource(resourcePath)
    val source = Source.fromURL(url)
    val str    = source.getLines().mkString(lineSeparator)
    source.close()
    str
  }

  def stubForMultipart(
    url: String,
    status: Integer,
    body: String         = "",
    headers: HttpHeaders = new HttpHeaders()): StubMapping =
    stubFor(
      post(urlMatching(url))
        .withMultipartRequestBody(aMultipart("acl")
          .withBody(equalTo("public-read-write")))
        .withMultipartRequestBody(aMultipart("Content-Type")
          .withBody(equalTo("application/text")))
        .withMultipartRequestBody(aMultipart("key")
          .withBody(equalTo("helloworld.txt")))
        .withMultipartRequestBody(aMultipart("error_action_redirect")
          .withBody(equalTo("https://www.amazon.co.uk")))
        .withMultipartRequestBody(aMultipart()
          .withHeader("Content-Disposition", equalTo("form-data; name=\"file\"; filename=\"helloworld.txt\""))
          .withHeader("Content-Type", equalTo("text/plain"))
          .withBody(equalTo("Hello World!")))
        .willReturn(
          aResponse()
            .withStatus(status)
            .withBody(body)
            .withHeaders(headers)
        ))

}
