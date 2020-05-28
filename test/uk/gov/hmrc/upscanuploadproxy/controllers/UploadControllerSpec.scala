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
import org.apache.http.client.utils.URIBuilder
import org.apache.http.message.BasicNameValuePair
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Span}
import play.api.libs.ws.WSResponse
import uk.gov.hmrc.integration.UrlHelper.-/

import scala.collection.JavaConverters._
import scala.io.Source

class UploadControllerSpec extends AcceptanceSpec with ScalaFutures {

  private def resource(path: String): String = s"http://localhost:$port/${-/(path)}"

  private def makeRequest(body: String, bucket: String= "bucketname"): WSResponse =
    wsClient
      .url(resource(s"v1/uploads/$bucket"))
      .withHttpHeaders(
        "Content-Type" -> "multipart/form-data; boundary=--------------------------946347039423050176633444")
      .withFollowRedirects(false)
      .post(body)
      .futureValue

  private def makeOptionsRequest(bucket: String= "bucketname"): WSResponse =
    wsClient
      .url(resource(s"v1/uploads/$bucket"))
      .withFollowRedirects(false)
      .options()
      .futureValue

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(scaled(Span(2000, Millis)), scaled(Span(50, Millis)))

  "OPTIONS to testHandlerRequest" must {

    "proxy query to S3" in {
      val body = "OptionsBody"
      stubForOptions("/s3", 200, body)
      val response = makeOptionsRequest()
      response.status mustBe 200
      response.body mustBe body
    }

    "disallow invalid bucket names" in {
      makeOptionsRequest( bucket = "www.baddomain.com%23").status mustBe 404
      makeOptionsRequest( bucket = "www.baddomain.com%40").status mustBe 404
      makeOptionsRequest( bucket = "www.baddomain.com%3A").status mustBe 404
    }

  }

  "POST to testHandleRequest" must {

    "proxy response on S3 2xx" in {
      stubForMultipart("/s3", 204)

      val postBody = readResource("/simple-request")
      val response = makeRequest(postBody)

      response.status mustBe 204
    }

    "proxy response on S3 3xx" in {

      val locationHeader = new HttpHeader("Location", "https://www.hmrc.gov.uk?a=a&b=b")
      stubForMultipart(url = "/s3", status = 303, headers = new HttpHeaders(locationHeader))

      val postBody = readResource("/simple-request")
      val response = makeRequest(postBody)

      response.status mustBe 303
      response.body mustBe ""
      response.header("Location") mustBe Some("https://www.hmrc.gov.uk?a=a&b=b")
    }

    "redirect on S3 4xx" in {
      stubForMultipart("/s3", 400, "<Error><Message>failure</Message></Error>")

      val postBody = readResource("/simple-request")
      val response = makeRequest(postBody)
      val responseLocationUrl = new URIBuilder(response.header("Location").getOrElse(""))

      response.status mustBe 303
      response.body mustBe ""
      responseLocationUrl.getHost mustBe "www.amazon.co.uk"
      responseLocationUrl.getQueryParams.asScala must contain theSameElementsAs Seq(
        new BasicNameValuePair("errorMessage", "failure"),
        new BasicNameValuePair("key", "b198de49-e7b5-49a8-83ff-068fc9357481")
      )
    }

    "redirect - without query parameter on S3 4xx when response xml can not be parsed" in {
      stubForMultipart("/s3", 404, "successful")

      val postBody = readResource("/simple-request")
      val response = makeRequest(postBody)
      val responseLocationUrl = new URIBuilder(response.header("Location").getOrElse(""))

      response.status mustBe 303
      response.body mustBe ""
      responseLocationUrl.getHost mustBe "www.amazon.co.uk"
      responseLocationUrl.getQueryParams.asScala must contain theSameElementsAs Seq(
        new BasicNameValuePair("key", "b198de49-e7b5-49a8-83ff-068fc9357481")
      )
    }

    "redirect on S3 5xx" in {
      stubForMultipart("/s3", 500, "<Error><Message>internal server error</Message></Error>")

      val postBody = readResource("/simple-request")
      val response = makeRequest(postBody)
      val responseLocationUrl = new URIBuilder(response.header("Location").getOrElse(""))

      response.status mustBe 303
      response.body mustBe ""
      responseLocationUrl.getHost mustBe "www.amazon.co.uk"
      responseLocationUrl.getQueryParams.asScala must contain theSameElementsAs Seq(
        new BasicNameValuePair("errorMessage", "internal server error"),
        new BasicNameValuePair("key", "b198de49-e7b5-49a8-83ff-068fc9357481")
      )
    }

    "return json error response on missing destination path parameter" in {

      val postBody = readResource("/simple-request")
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

      val postBody = readResource("/simple-request")
      val response = wsClient
        .url(resource("v1/uploads/bucketname"))
        .withFollowRedirects(false)
        .post(postBody)
        .futureValue

      response.status mustBe 400
      response.body mustBe "{\"message\":\"Bad request: Missing boundary header\"}"
      response.header("Content-Type") mustBe Some("application/json")
    }

    "return json error response when error_action_redirect is missing" in {

      val postBody = readResource("/request-missing-error-action-redirect")
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

    "ignore invalid bucket names" in {

      val locationHeader = new HttpHeader("Location", "https://www.hmrc.gov.uk?a=a&b=b")
      stubForMultipart(url = "/s3", status = 303, headers = new HttpHeaders(locationHeader))

      val postBody = readResource("/simple-request")
      makeRequest(postBody, bucket = "www.baddomain.com%23").status mustBe 404
      makeRequest(postBody, bucket = "www.baddomain.com%40").status mustBe 404
      makeRequest(postBody, bucket = "www.baddomain.com%3A").status mustBe 404
    }
  }

  private def readResource(resourcePath: String, lineSeparator: String = LineSeparator.Windows): String = {

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
          .withBody(equalTo("b198de49-e7b5-49a8-83ff-068fc9357481")))
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

  def stubForOptions(url: String, status: Integer, body:String): StubMapping =
    stubFor(options(urlMatching(url))
      .willReturn(
        aResponse()
          .withStatus(status)
          .withBody(body)
      ))
}
