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

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.matching.MultipartValuePatternBuilder
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import com.sun.org.apache.xml.internal.serialize.LineSeparator
import org.apache.http.client.utils.URIBuilder
import org.apache.http.message.BasicNameValuePair
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Span}
import play.api.http.Status._
import play.api.libs.ws.{WSRequest, WSResponse}
import play.mvc.Http.HeaderNames.{CONTENT_TYPE, LOCATION}
import play.mvc.Http.MimeTypes.JSON
import uk.gov.hmrc.integration.UrlHelper.-/

import scala.collection.JavaConverters._
import scala.io.Source

class UploadControllerSpec extends AcceptanceSpec with ScalaFutures {

  import UploadControllerSpec._

  private def makeUploadRequest(withBody: String, bucket: String= "bucketname"): WSResponse =
    createRequest(bucket)
      .withHttpHeaders(CONTENT_TYPE -> MultipartFormDataContentTypeHeaderValue)
      .post(withBody)
      .futureValue

  private def makePassThroughRequest(bucket: String = "bucketname"): WSResponse =
    createRequest(bucket)
      .options()
      .futureValue

  private def createRequest(bucket: String): WSRequest =
    wsClient
      .url(resource(s"v1/uploads/$bucket"))
      .withFollowRedirects(false)

  private def resource(path: String): String = s"http://localhost:$port/${-/(path)}"

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(scaled(Span(2000, Millis)), scaled(Span(50, Millis)))

  "OPTIONS to testHandlerRequest" must {

    "proxy query to S3" in {
      val responseBody = "OptionsBody"
      stubS3ForOptions(OK, responseBody)

      val response = makePassThroughRequest()

      response.status mustBe OK
      response.body mustBe responseBody
    }

    "disallow invalid bucket names" in {
      makePassThroughRequest(bucket = "www.baddomain.com%23").status mustBe NOT_FOUND
      makePassThroughRequest(bucket = "www.baddomain.com%40").status mustBe NOT_FOUND
      makePassThroughRequest(bucket = "www.baddomain.com%3A").status mustBe NOT_FOUND
    }

  }

  "POST to testHandleRequest" must {

    "proxy response on S3 2xx when upload success redirect is specified" in {
      val expectedMultipartFormData = RequestMultipartFormDataExcludingRedirects ++ Seq(
        aMultipart("success_action_redirect").withBody(equalTo("https://myservice.com/nextPage")),
        aMultipart("error_action_redirect").withBody(equalTo("https://myservice.com/errorPage"))
      )
      stubS3ForPost(expectedMultipartFormData, willReturn = aResponse().withStatus(NO_CONTENT))

      val response = makeUploadRequest(withBody = readResource("/request-with-success-action-redirect"))

      response.status mustBe NO_CONTENT
    }

    "proxy response on S3 2xx when upload success redirect is omitted" in {
      val expectedMultipartFormData = RequestMultipartFormDataExcludingRedirects :+
        aMultipart("error_action_redirect").withBody(equalTo("https://myservice.com/errorPage"))
      stubS3ForPost(expectedMultipartFormData, willReturn = aResponse().withStatus(NO_CONTENT))

      val response = makeUploadRequest(withBody = readResource("/request-no-success-action-redirect"))

      response.status mustBe NO_CONTENT
    }

    "proxy response on S3 3xx" in {
      val LocationHeaderValue = "https://www.hmrc.gov.uk?a=a&b=b"
      val s3Response = aResponse().withStatus(SEE_OTHER).withHeader(LOCATION, LocationHeaderValue)
      stubS3ForPost(SimpleRequestMultipartFormData, willReturn = s3Response)

      val response = makeUploadRequest(withBody = readResource("/simple-request"))

      response.status mustBe SEE_OTHER
      response.body mustBe empty
      response.header(LOCATION) must contain (LocationHeaderValue)
    }

    "redirect on S3 4xx" in {
      val expectedMultipartFormData = RequestMultipartFormDataExcludingRedirects :+
        aMultipart("error_action_redirect").withBody(equalTo("https://myservice.com/errorPage"))
      val s3Response = aResponse().withStatus(BAD_REQUEST).withBody("<Error><Message>failure</Message></Error>")
      stubS3ForPost(expectedMultipartFormData, willReturn = s3Response)

      val response = makeUploadRequest(withBody = readResource("/request-no-success-action-redirect"))
      val responseLocationUrl = new URIBuilder(response.header(LOCATION).getOrElse(""))

      response.status mustBe SEE_OTHER
      response.body mustBe empty
      responseLocationUrl.getHost mustBe "myservice.com"
      responseLocationUrl.getQueryParams.asScala must contain theSameElementsAs Seq(
        new BasicNameValuePair("errorMessage", "failure"),
        new BasicNameValuePair("key", "b198de49-e7b5-49a8-83ff-068fc9357481")
      )
    }

    "redirect - without query parameter on S3 4xx when response xml can not be parsed" in {
      val expectedMultipartFormData = RequestMultipartFormDataExcludingRedirects :+
        aMultipart("error_action_redirect").withBody(equalTo("https://myservice.com/errorPage"))
      val s3Response = aResponse().withStatus(BAD_REQUEST).withBody("non-xml-body")
      stubS3ForPost(expectedMultipartFormData, willReturn = s3Response)

      val response = makeUploadRequest(withBody = readResource("/request-no-success-action-redirect"))
      val responseLocationUrl = new URIBuilder(response.header(LOCATION).getOrElse(""))

      response.status mustBe SEE_OTHER
      response.body mustBe empty
      responseLocationUrl.getHost mustBe "myservice.com"
      responseLocationUrl.getQueryParams.asScala must contain theSameElementsAs Seq(
        new BasicNameValuePair("key", "b198de49-e7b5-49a8-83ff-068fc9357481")
      )
    }

    "redirect on S3 5xx" in {
      val expectedMultipartFormData = RequestMultipartFormDataExcludingRedirects :+
        aMultipart("error_action_redirect").withBody(equalTo("https://myservice.com/errorPage"))
      val s3Response = aResponse().withStatus(INTERNAL_SERVER_ERROR).withBody("<Error><Message>internal server error</Message></Error>")
      stubS3ForPost(expectedMultipartFormData, willReturn = s3Response)

      val response = makeUploadRequest(withBody = readResource("/request-no-success-action-redirect"))
      val responseLocationUrl = new URIBuilder(response.header(LOCATION).getOrElse(""))

      response.status mustBe SEE_OTHER
      response.body mustBe empty
      responseLocationUrl.getHost mustBe "myservice.com"
      responseLocationUrl.getQueryParams.asScala must contain theSameElementsAs Seq(
        new BasicNameValuePair("errorMessage", "internal server error"),
        new BasicNameValuePair("key", "b198de49-e7b5-49a8-83ff-068fc9357481")
      )
    }

    "return json error response on missing destination path parameter" in {
      val response = wsClient
        .url(resource("v1/uploads"))
        .withHttpHeaders(CONTENT_TYPE -> MultipartFormDataContentTypeHeaderValue)
        .withFollowRedirects(false)
        .post(readResource("/simple-request"))
        .futureValue

      response.status mustBe NOT_FOUND
      response.body mustBe """{"message":"Path '/v1/uploads' not found."}"""
      response.header(CONTENT_TYPE) must contain (JSON)
    }

    "return json error response on missing content type" in {
      val response = wsClient
        .url(resource("v1/uploads/bucketname"))
        .withFollowRedirects(false)
        .post(readResource("/simple-request"))
        .futureValue

      response.status mustBe BAD_REQUEST
      response.body mustBe """{"message":"Bad request: Missing boundary header"}"""
      response.header(CONTENT_TYPE) must contain (JSON)
    }

    "return json error response when error_action_redirect is missing" in {
      val response = makeUploadRequest(withBody = readResource("/request-missing-error-action-redirect"))

      response.status mustBe BAD_REQUEST
      response.body mustBe """{"message":"Could not find error_action_redirect field in request"}"""
      response.header(CONTENT_TYPE) must contain (JSON)
    }

    "return json error response when request cannot be parsed" in {
      val response = makeUploadRequest(withBody = "cannot be parsed\n")

      response.status mustBe BAD_REQUEST
      response.body mustBe """{"message":"Bad request: Unexpected end of input"}"""
      response.header(CONTENT_TYPE) must contain (JSON)
    }

    "ignore invalid bucket names" in {
      val postBody = readResource("/simple-request")

      makeUploadRequest(postBody, bucket = "www.baddomain.com%23").status mustBe NOT_FOUND
      makeUploadRequest(postBody, bucket = "www.baddomain.com%40").status mustBe NOT_FOUND
      makeUploadRequest(postBody, bucket = "www.baddomain.com%3A").status mustBe NOT_FOUND
    }
  }

  private def readResource(resourcePath: String, lineSeparator: String = LineSeparator.Windows): String = {
    val url    = getClass.getResource(resourcePath)
    val source = Source.fromURL(url)
    val str    = source.getLines().mkString(lineSeparator)
    source.close()
    str
  }

  private def stubS3ForPost(expectedMultipartBuilder: Seq[MultipartValuePatternBuilder],
                            willReturn: ResponseDefinitionBuilder): StubMapping = {
    val postUrl = post(urlEqualTo(S3Path))
    val postMultipartFormDataWillReturn = expectedMultipartBuilder.foldLeft(postUrl) { (acc, multipart) =>
      acc.withMultipartRequestBody(multipart)
    }.willReturn(
      willReturn
    )
    stubFor(postMultipartFormDataWillReturn)
  }

  private def stubS3ForOptions(responseStatus: Int, responseBody:String): StubMapping =
    stubFor(options(urlEqualTo(S3Path))
      .willReturn(
        aResponse()
          .withStatus(responseStatus)
          .withBody(responseBody)
      ))
}

private object UploadControllerSpec {
  // must match sample request files
  val MultipartFormDataContentTypeHeaderValue = "multipart/form-data; boundary=--------------------------946347039423050176633444"

  val RequestMultipartFormDataExcludingRedirects = Seq(
    aMultipart("acl").withBody(equalTo("public-read-write")),
    aMultipart("Content-Type").withBody(equalTo("application/text")),
    aMultipart("key").withBody(equalTo("b198de49-e7b5-49a8-83ff-068fc9357481")),
    aMultipart()
      .withHeader("Content-Disposition", equalTo("form-data; name=\"file\"; filename=\"helloworld.txt\""))
      .withHeader("Content-Type", equalTo("text/plain"))
      .withBody(equalTo("Hello World!"))
  )

  val SimpleRequestMultipartFormData = RequestMultipartFormDataExcludingRedirects :+
    aMultipart("error_action_redirect").withBody(equalTo("https://www.amazon.co.uk"))
}