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

package uk.gov.hmrc.upscanuploadproxy.controller

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.matching.MultipartValuePatternBuilder
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import com.sun.org.apache.xml.internal.serialize.LineSeparator
import org.apache.http.client.utils.URIBuilder
import org.apache.http.message.BasicNameValuePair
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import play.api.http.Status._
import play.api.libs.json.Json
import play.api.libs.ws.{WSRequest, WSResponse, writeableOf_String}
import play.api.libs.ws.WSBodyReadables.readableAsString
import play.api.test.Helpers.TRANSFER_ENCODING
import play.mvc.Http.HeaderNames.{CONTENT_LENGTH, CONTENT_TYPE, ETAG, LOCATION}
import play.mvc.Http.MimeTypes.{JSON, XML}
import uk.gov.hmrc.upscanuploadproxy.controller.UrlHelper.-/

import scala.io.Source
import scala.jdk.CollectionConverters._

class UploadControllerSpec
  extends AcceptanceSpec
     with ScalaFutures
     with IntegrationPatience:

  import UploadControllerSpec._

  private def makeUploadRequest(withBody: String, bucket: String = "bucketname"): WSResponse =
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

  private def resource(path: String): String =
    s"http://localhost:$port/${-/(path)}"

  "OPTIONS to testHandlerRequest" should:
    "proxy query to S3" in:
      val responseBody = "OptionsBody"
      stubS3ForOptions(OK, responseBody)

      val response = makePassThroughRequest()

      response.status shouldBe OK
      response.body   shouldBe responseBody

    "disallow invalid bucket names" in:
      makePassThroughRequest(bucket = "www.baddomain.com%23").status shouldBe NOT_FOUND
      makePassThroughRequest(bucket = "www.baddomain.com%40").status shouldBe NOT_FOUND
      makePassThroughRequest(bucket = "www.baddomain.com%3A").status shouldBe NOT_FOUND

  "POST to testHandleRequest" should:

    /*
     * Note that success redirects are handled by AWS not us.
     * This test is documenting that the field is passed through if set.
     * We should simply proxy any 2xx or 3xx responses.
     */
    "proxy response on S3 2xx when upload success redirect is specified" in:
      val expectedMultipartFormData =
        RequestMultipartFormDataExcludingRedirects
          ++ Seq(aMultipart("success_action_redirect").withBody(equalTo("https://myservice.com/nextPage")))
      stubS3ForPost(expectedMultipartFormData, willReturn = aResponse().withStatus(NO_CONTENT))

      val response = makeUploadRequest(withBody = readResource("/request-with-success-action-redirect"))

      response.status shouldBe NO_CONTENT

    /*
     * This documents current behaviour.
     * Technically the error_action_redirect field is for us, and does not need to be forwarded onto AWS.
     */
    "proxy response on S3 2xx when upload error redirect is specified" in:
      val expectedMultipartFormData =
        RequestMultipartFormDataExcludingRedirects
          ++ Seq(aMultipart("error_action_redirect").withBody(equalTo("https://myservice.com/errorPage")))
      stubS3ForPost(expectedMultipartFormData, willReturn = aResponse().withStatus(NO_CONTENT))

      val response = makeUploadRequest(withBody = readResource("/request-with-error-action-redirect"))

      response.status shouldBe NO_CONTENT

    "proxy response on S3 2xx when both upload success & error redirects are omitted" in:
      stubS3ForPost(RequestMultipartFormDataExcludingRedirects, willReturn = aResponse().withStatus(NO_CONTENT))

      val response = makeUploadRequest(withBody = readResource("/request-no-action-redirects"))

      response.status shouldBe NO_CONTENT

    "proxy response on S3 2xx retaining upstream response headers" in:
      val headerName  = "X-Some-Header-Name"
      val headerValue = "X-Some-Header-Value"
      val s3Response  = aResponse().withStatus(NO_CONTENT).withHeader(headerName, headerValue)
      stubS3ForPost(RequestMultipartFormDataExcludingRedirects, willReturn = s3Response)

      val response = makeUploadRequest(withBody = readResource("/request-no-action-redirects"))

      response.header(headerName) should contain (headerValue)

    "proxy response on S3 3xx" in:
      val locationHeaderValue = "https://www.hmrc.gov.uk?a=a&b=b"
      val s3Response          = aResponse().withStatus(SEE_OTHER).withHeader(LOCATION, locationHeaderValue)
      stubS3ForPost(SimpleRequestMultipartFormData, willReturn = s3Response)

      val response = makeUploadRequest(withBody = readResource("/simple-request"))

      response.status shouldBe SEE_OTHER
      response.body   shouldBe empty
      response.header(LOCATION) should contain (locationHeaderValue)

    "proxy response on S3 3xx retaining upstream response headers" in:
      val headerName  = "X-Some-Header-Name"
      val headerValue = "X-Some-Header-Value"
      val s3Response =
        aResponse()
          .withStatus(SEE_OTHER)
          .withHeader(LOCATION, "https://www.hmrc.gov.uk?a=a&b=b")
          .withHeader(headerName, headerValue)
      stubS3ForPost(RequestMultipartFormDataExcludingRedirects, willReturn = s3Response)

      val response = makeUploadRequest(withBody = readResource("/request-no-action-redirects"))

      response.header(headerName) should contain (headerValue)

    "redirect on S3 4xx when error redirect is specified" in:
      val expectedMultipartFormData =
        RequestMultipartFormDataExcludingRedirects
          :+ aMultipart("error_action_redirect").withBody(equalTo("https://myservice.com/errorPage"))
      val s3Response = aResponse().withStatus(BAD_REQUEST).withHeader(CONTENT_TYPE, XML).withBody(FullAwsError)
      stubS3ForPost(expectedMultipartFormData, willReturn = s3Response)

      val response            = makeUploadRequest(withBody = readResource("/request-with-error-action-redirect"))
      val responseLocationUrl = URIBuilder(response.header(LOCATION).getOrElse(""))

      response.status             shouldBe SEE_OTHER
      response.body               shouldBe empty
      responseLocationUrl.getHost shouldBe "myservice.com"
      responseLocationUrl.getPath shouldBe "/errorPage"
      responseLocationUrl.getQueryParams.asScala should contain theSameElementsAs
        Seq(
          BasicNameValuePair("key", "b198de49-e7b5-49a8-83ff-068fc9357481"),
          BasicNameValuePair("errorCode", "NoSuchKey"),
          BasicNameValuePair("errorMessage", "The resource you requested does not exist"),
          BasicNameValuePair("errorResource", "/mybucket/myfoto.jpg"),
          BasicNameValuePair("errorRequestId", "4442587FB7D0A2F9")
        )

    "redirect on S3 4xx when error redirect is specified retaining allowed upstream response headers" in:
      val expectedMultipartFormData =
        RequestMultipartFormDataExcludingRedirects
          :+ aMultipart("error_action_redirect").withBody(equalTo("https://myservice.com/errorPage"))

      val xAmzIdHeaderValue = "some-aws-id"
      val accessControlAllowOriginHeaderValue = "https://myservice.com"

      val s3Response =
        aResponse()
          .withStatus(BAD_REQUEST)
          .withHeader(CONTENT_TYPE, XML)
          .withHeader(TRANSFER_ENCODING, ChunkedTransferEncoding)
          .withHeader(ETAG, "some-etag")
          .withHeader(XAmzId, xAmzIdHeaderValue)
          .withHeader(AccessControlAllowOrigin, accessControlAllowOriginHeaderValue)
          .withBody(FullAwsError)
      stubS3ForPost(expectedMultipartFormData, willReturn = s3Response)

      val response = makeUploadRequest(withBody = readResource("/request-with-error-action-redirect"))

      // retain allowed AWS response headers
      response.header(XAmzId)                   should contain(xAmzIdHeaderValue)
      response.header(AccessControlAllowOrigin) should contain(accessControlAllowOriginHeaderValue)
      // redirect has no body so any AWS response headers relating to the content should be excluded
      response.headers should not contain key (CONTENT_TYPE)
      response.headers should not contain key (TRANSFER_ENCODING)
      response.headers should not contain key (ETAG)

    "proxy status code with error translation on S3 4xx when error redirect is not specified" in:
      val fullAwsErrorAsJson =
        Json.parse:
          """{
          | "key": "b198de49-e7b5-49a8-83ff-068fc9357481",
          | "errorCode": "NoSuchKey",
          | "errorMessage": "The resource you requested does not exist",
          | "errorResource": "/mybucket/myfoto.jpg",
          | "errorRequestId": "4442587FB7D0A2F9"
          |}""".stripMargin

      val s3Response = aResponse().withStatus(BAD_REQUEST).withHeader(CONTENT_TYPE, XML).withBody(FullAwsError)
      stubS3ForPost(RequestMultipartFormDataExcludingRedirects, willReturn = s3Response)

      val response = makeUploadRequest(withBody = readResource("/request-no-action-redirects"))

      response.status           shouldBe BAD_REQUEST
      response.contentType      shouldBe JSON
      Json.parse(response.body) shouldBe fullAwsErrorAsJson

    "proxy status code with error translation on S3 AccessDenied error_action_redirect policy condition failed" in:
      val fullAwsErrorAsJson =
        Json.parse:
          """{
          | "key": "b198de49-e7b5-49a8-83ff-068fc9357481",
          | "errorCode": "AccessDenied",
          | "errorMessage": "Invalid according to Policy: Policy Condition failed: [\"eq\", \"$error_action_redirect\", \"https://some-service/error\"]",
          | "errorResource": "/mybucket/myfoto.jpg",
          | "errorRequestId": "4442587FB7D0A2F9"
          |}""".stripMargin

      val s3Response = aResponse().withStatus(FORBIDDEN).withHeader(CONTENT_TYPE, XML).withBody(ErrorRedirectPolicyConditionFailedResponse)
      stubS3ForPost(RequestMultipartFormDataExcludingRedirects, willReturn = s3Response)

      val response = makeUploadRequest(withBody = readResource("/request-with-error-action-redirect"))

      response.status           shouldBe FORBIDDEN
      response.contentType      shouldBe JSON
      Json.parse(response.body) shouldBe fullAwsErrorAsJson

    "proxy status code with error translation on S3 4xx when error redirect is not specified retaining allowed upstream response headers" in:
      val xAmzIdHeaderValue = "some-aws-id"
      val accessControlAllowOriginHeaderValue = "https://myservice.com"
      val s3Response = aResponse()
        .withStatus(BAD_REQUEST)
        .withHeader(CONTENT_TYPE, XML)
        .withHeader(TRANSFER_ENCODING, ChunkedTransferEncoding)
        .withHeader(ETAG, "some-etag")
        .withHeader(XAmzId, xAmzIdHeaderValue)
        .withHeader(AccessControlAllowOrigin, accessControlAllowOriginHeaderValue)
        .withBody(FullAwsError)

      stubS3ForPost(RequestMultipartFormDataExcludingRedirects, willReturn = s3Response)

      val response = makeUploadRequest(withBody = readResource("/request-no-action-redirects"))

      // retain allowed AWS response headers
      response.header(XAmzId)                   should contain(xAmzIdHeaderValue)
      response.header(AccessControlAllowOrigin) should contain(accessControlAllowOriginHeaderValue)
      // content-related headers should reflect our adapted payload not the AWS body
      response.contentType shouldBe JSON
      response.headers should contain key CONTENT_LENGTH.toLowerCase
      response.headers should not contain key (TRANSFER_ENCODING)
      response.headers should not contain key (ETAG)

    "redirect on S3 4xx when error redirect is specified - without errorMessage query parameter when response xml cannot be parsed" in:
      val expectedMultipartFormData = RequestMultipartFormDataExcludingRedirects :+
        aMultipart("error_action_redirect").withBody(equalTo("https://myservice.com/errorPage"))
      val s3Response = aResponse().withStatus(BAD_REQUEST).withHeader(CONTENT_TYPE, XML).withBody("non-xml-body")
      stubS3ForPost(expectedMultipartFormData, willReturn = s3Response)

      val response = makeUploadRequest(withBody = readResource("/request-with-error-action-redirect"))
      val responseLocationUrl = URIBuilder(response.header(LOCATION).getOrElse(""))

      response.status             shouldBe SEE_OTHER
      response.body               shouldBe empty
      responseLocationUrl.getHost shouldBe "myservice.com"
      responseLocationUrl.getPath shouldBe "/errorPage"
      responseLocationUrl.getQueryParams.asScala should contain theSameElementsAs Seq(
        BasicNameValuePair("key", "b198de49-e7b5-49a8-83ff-068fc9357481")
      )

    "proxy status code without error details on S3 4xx when error redirect is not specified and response xml cannot be parsed" in:
      val keyOnlyAsJson =
        Json.parse("""{"key": "b198de49-e7b5-49a8-83ff-068fc9357481"}""")

      val s3Response = aResponse().withStatus(BAD_REQUEST).withHeader(CONTENT_TYPE, XML).withBody("non-xml-body")
      stubS3ForPost(RequestMultipartFormDataExcludingRedirects, willReturn = s3Response)

      val response = makeUploadRequest(withBody = readResource("/request-no-action-redirects"))

      response.status           shouldBe BAD_REQUEST
      response.contentType      shouldBe JSON
      Json.parse(response.body) shouldBe keyOnlyAsJson

    /*
     * This test documents that we do not explicitly do anything to handle a missing upload file.
     * The only fields of the multipart body we explicitly look at and validate relate to the error redirect URL.
     * We will happily forward a body without a file, and so rely on AWS to handle this appropriately.
     */
    "handle missing file" in:
      val expectedMultipartFormData =
        Seq(
          aMultipart("acl"                  ).withBody(equalTo("public-read-write")),
          aMultipart("Content-Type"         ).withBody(equalTo("application/text")),
          aMultipart("key"                  ).withBody(equalTo("b198de49-e7b5-49a8-83ff-068fc9357481")),
          aMultipart("error_action_redirect").withBody(equalTo("https://myservice.com/errorPage"))
        )
      val s3Response = aResponse().withStatus(BAD_REQUEST).withBody("<Error><Message>missing file</Message></Error>")
      stubS3ForPost(expectedMultipartFormData, willReturn = s3Response)

      val response = makeUploadRequest(withBody = readResource("/request-missing-file"))
      val responseLocationUrl = URIBuilder(response.header(LOCATION).getOrElse(""))

      response.status             shouldBe SEE_OTHER
      response.body               shouldBe empty
      responseLocationUrl.getHost shouldBe "myservice.com"
      responseLocationUrl.getPath shouldBe "/errorPage"
      responseLocationUrl.getQueryParams.asScala should contain theSameElementsAs Seq(
        BasicNameValuePair("errorMessage", "missing file"),
        BasicNameValuePair("key", "b198de49-e7b5-49a8-83ff-068fc9357481")
      )

    "redirect on S3 5xx when error redirect is specified" in:
      val expectedMultipartFormData =
        RequestMultipartFormDataExcludingRedirects
          :+ aMultipart("error_action_redirect").withBody(equalTo("https://myservice.com/errorPage"))
      val s3Response =
        aResponse()
          .withStatus(INTERNAL_SERVER_ERROR)
          .withHeader(CONTENT_TYPE, XML)
          .withBody("<Error><Message>internal server error</Message></Error>")
      stubS3ForPost(expectedMultipartFormData, willReturn = s3Response)

      val response            = makeUploadRequest(withBody = readResource("/request-with-error-action-redirect"))
      val responseLocationUrl = URIBuilder(response.header(LOCATION).getOrElse(""))

      response.status             shouldBe SEE_OTHER
      response.body               shouldBe empty
      responseLocationUrl.getHost shouldBe "myservice.com"
      responseLocationUrl.getPath shouldBe "/errorPage"
      responseLocationUrl.getQueryParams.asScala should contain theSameElementsAs Seq(
        BasicNameValuePair("errorMessage", "internal server error"),
        BasicNameValuePair("key"         , "b198de49-e7b5-49a8-83ff-068fc9357481")
      )

    "redirect on S3 5xx when error redirect is specified retaining allowed upstream response headers" in:
      val expectedMultipartFormData =
        RequestMultipartFormDataExcludingRedirects
          :+ aMultipart("error_action_redirect").withBody(equalTo("https://myservice.com/errorPage"))
      val xAmzIdHeaderValue = "some-aws-id"
      val accessControlAllowOriginHeaderValue = "https://myservice.com"
      val s3Response =
        aResponse()
          .withStatus(INTERNAL_SERVER_ERROR)
          .withHeader(CONTENT_TYPE, XML)
          .withHeader(TRANSFER_ENCODING, ChunkedTransferEncoding)
          .withHeader(ETAG, "some-etag")
          .withHeader(XAmzId, xAmzIdHeaderValue)
          .withHeader(AccessControlAllowOrigin, accessControlAllowOriginHeaderValue)
          .withBody("<Error><Message>internal server error</Message></Error>")
      stubS3ForPost(expectedMultipartFormData, willReturn = s3Response)

      val response = makeUploadRequest(withBody = readResource("/request-with-error-action-redirect"))

      // retain allowed AWS response headers
      response.header(XAmzId)                   should contain(xAmzIdHeaderValue)
      response.header(AccessControlAllowOrigin) should contain(accessControlAllowOriginHeaderValue)
      // redirect has no body so any AWS response headers relating to the content should be excluded
      response.headers should not contain key (CONTENT_TYPE)
      response.headers should not contain key (TRANSFER_ENCODING)
      response.headers should not contain key (ETAG)

    "proxy status code with error translation on S3 5xx when error redirect is not specified" in:
      val s3Response =
        aResponse()
          .withStatus(INTERNAL_SERVER_ERROR)
          .withHeader(CONTENT_TYPE, XML)
          .withBody("<Error><Message>internal server error</Message></Error>")
      stubS3ForPost(RequestMultipartFormDataExcludingRedirects, willReturn = s3Response)
      val aAwsErrorAsJson =
        Json.parse:
          """{
          | "key": "b198de49-e7b5-49a8-83ff-068fc9357481",
          | "errorMessage": "internal server error"
          |}""".stripMargin

      val response = makeUploadRequest(withBody = readResource("/request-no-action-redirects"))

      response.status           shouldBe INTERNAL_SERVER_ERROR
      response.contentType      shouldBe JSON
      Json.parse(response.body) shouldBe aAwsErrorAsJson

    "proxy status code with error translation on S3 5xx when error redirect is not specified retaining allowed upstream response headers" in:
      val xAmzIdHeaderValue                   = "some-aws-id"
      val accessControlAllowOriginHeaderValue = "https://myservice.com"
      val s3Response =
        aResponse()
          .withStatus(INTERNAL_SERVER_ERROR)
          .withHeader(CONTENT_TYPE, XML)
          .withHeader(TRANSFER_ENCODING, ChunkedTransferEncoding)
          .withHeader(ETAG, "some-etag")
          .withHeader(XAmzId, xAmzIdHeaderValue)
          .withHeader(AccessControlAllowOrigin, accessControlAllowOriginHeaderValue)
          .withBody("<Error><Message>internal server error</Message></Error>")
      stubS3ForPost(RequestMultipartFormDataExcludingRedirects, willReturn = s3Response)

      val response = makeUploadRequest(withBody = readResource("/request-no-action-redirects"))

      // retain allowed AWS response headers
      response.header(XAmzId)                   should contain(xAmzIdHeaderValue)
      response.header(AccessControlAllowOrigin) should contain (accessControlAllowOriginHeaderValue)
      // content-related headers which should reflect our adapted payload not the AWS body
      response.contentType shouldBe JSON
      response.headers should contain key CONTENT_LENGTH.toLowerCase
      response.headers should not contain key (TRANSFER_ENCODING)
      response.headers should not contain key (ETAG)

    "redirect on S3 5xx when error redirect is specified - without errorMessage query parameter when response xml cannot be parsed" in:
      val expectedMultipartFormData =
        RequestMultipartFormDataExcludingRedirects
          :+ aMultipart("error_action_redirect").withBody(equalTo("https://myservice.com/errorPage"))
      val s3Response = aResponse().withStatus(SERVICE_UNAVAILABLE).withHeader(CONTENT_TYPE, XML).withBody("non-xml-body")
      stubS3ForPost(expectedMultipartFormData, willReturn = s3Response)

      val response            = makeUploadRequest(withBody = readResource("/request-with-error-action-redirect"))
      val responseLocationUrl = URIBuilder(response.header(LOCATION).getOrElse(""))

      response.status             shouldBe SEE_OTHER
      response.body               shouldBe empty
      responseLocationUrl.getHost shouldBe "myservice.com"
      responseLocationUrl.getPath shouldBe "/errorPage"
      responseLocationUrl.getQueryParams.asScala should contain theSameElementsAs Seq(
        BasicNameValuePair("key", "b198de49-e7b5-49a8-83ff-068fc9357481")
      )

    "proxy status code without error details on S3 5xx when error redirect is not specified and response xml cannot be parsed" in:
      val keyOnlyAsJson =
        Json.parse("""{"key": "b198de49-e7b5-49a8-83ff-068fc9357481"}""")
      val s3Response = aResponse().withStatus(SERVICE_UNAVAILABLE).withHeader(CONTENT_TYPE, XML).withBody("non-xml-body")
      stubS3ForPost(RequestMultipartFormDataExcludingRedirects, willReturn = s3Response)

      val response = makeUploadRequest(withBody = readResource("/request-no-action-redirects"))

      response.status      shouldBe SERVICE_UNAVAILABLE
      response.contentType shouldBe JSON
      Json.parse(response.body) shouldBe keyOnlyAsJson

    "return json error response on missing destination path parameter" in:
      val response =
        wsClient
          .url(resource("v1/uploads"))
          .withHttpHeaders(CONTENT_TYPE -> MultipartFormDataContentTypeHeaderValue)
          .withFollowRedirects(false)
          .post(readResource("/simple-request"))
          .futureValue

      response.status shouldBe NOT_FOUND
      response.body   shouldBe """{"message":"Path '/v1/uploads' not found."}"""
      response.header(CONTENT_TYPE) should contain (JSON)

    "return json error response on missing content type" in:
      val response =
        wsClient
          .url(resource("v1/uploads/bucketname"))
          .withFollowRedirects(false)
          .post(readResource("/simple-request"))
          .futureValue

      response.status shouldBe BAD_REQUEST
      response.body   shouldBe """{"message":"Bad request: Missing boundary header"}"""
      response.header(CONTENT_TYPE) should contain (JSON)

    "return json error response when request cannot be parsed as a multipart form" in:
      val response = makeUploadRequest(withBody = "cannot be parsed\n")

      response.status shouldBe BAD_REQUEST
      response.body   shouldBe """{"message":"Bad request: Unexpected end of input"}"""
      response.header(CONTENT_TYPE) should contain (JSON)

    "return json error response when error redirect url is invalid" in:
      val response = makeUploadRequest(withBody = readResource("/request-invalid-error-action-redirect"))

      response.status shouldBe BAD_REQUEST
      response.body   shouldBe """{"message":"Unable to build valid redirect URL for error action"}"""
      response.header(CONTENT_TYPE) should contain (JSON)

    "return json error response when key is missing" in:
      val response = makeUploadRequest(withBody = readResource("/request-missing-key"))

      response.status shouldBe BAD_REQUEST
      response.body   shouldBe """{"message":"Could not find key field in request"}"""
      response.header(CONTENT_TYPE) should contain (JSON)

    "ignore invalid bucket names" in:
      val postBody = readResource("/simple-request")

      makeUploadRequest(postBody, bucket = "www.baddomain.com%23").status shouldBe NOT_FOUND
      makeUploadRequest(postBody, bucket = "www.baddomain.com%40").status shouldBe NOT_FOUND
      makeUploadRequest(postBody, bucket = "www.baddomain.com%3A").status shouldBe NOT_FOUND

  private def readResource(resourcePath: String, lineSeparator: String = LineSeparator.Windows): String =
    val url    = getClass.getResource(resourcePath)
    val source = Source.fromURL(url)
    val str    = source.getLines().mkString(lineSeparator)
    source.close()
    str

  private def stubS3ForPost(
    expectedMultipartBuilder: Seq[MultipartValuePatternBuilder],
    willReturn              : ResponseDefinitionBuilder): StubMapping =
    val postUrl = post(urlEqualTo(S3Path))
    val postMultipartFormDataWillReturn =
      expectedMultipartBuilder
        .foldLeft(postUrl)((acc, multipart) => acc.withMultipartRequestBody(multipart))
        .willReturn(willReturn)
    stubFor(postMultipartFormDataWillReturn)

  private def stubS3ForOptions(responseStatus: Int, responseBody: String): StubMapping =
    stubFor(options(urlEqualTo(S3Path))
      .willReturn(
        aResponse()
          .withStatus(responseStatus)
          .withBody(responseBody)
      )
    )

end UploadControllerSpec

private object UploadControllerSpec:

  // should match sample request files
  val MultipartFormDataContentTypeHeaderValue = "multipart/form-data; boundary=--------------------------946347039423050176633444"

  val RequestMultipartFormDataExcludingRedirects =
    Seq(
      aMultipart("acl"         ).withBody(equalTo("public-read-write")),
      aMultipart("Content-Type").withBody(equalTo("application/text")),
      aMultipart("key"         ).withBody(equalTo("b198de49-e7b5-49a8-83ff-068fc9357481")),
      aMultipart()
        .withHeader("Content-Disposition", equalTo("form-data; name=\"file\"; filename=\"helloworld.txt\""))
        .withHeader("Content-Type", equalTo("text/plain"))
        .withBody(equalTo("Hello World!"))
    )

  val SimpleRequestMultipartFormData =
    RequestMultipartFormDataExcludingRedirects
      :+ aMultipart("error_action_redirect").withBody(equalTo("https://myservice.com/errorPage"))

  val FullAwsError =
    """|<?xml version="1.0" encoding="UTF-8"?>
       |<Error>
       |  <Code>NoSuchKey</Code>
       |  <Message>The resource you requested does not exist</Message>
       |  <Resource>/mybucket/myfoto.jpg</Resource>
       |  <RequestId>4442587FB7D0A2F9</RequestId>
       |</Error>""".stripMargin

  val ErrorRedirectPolicyConditionFailedResponse =
    """<?xml version="1.0" encoding="UTF-8"?>
      |<Error>
      |    <Code>AccessDenied</Code>
      |    <Message>Invalid according to Policy: Policy Condition failed: ["eq", "$error_action_redirect", "https://some-service/error"]</Message>
      |    <Resource>/mybucket/myfoto.jpg</Resource>
      |    <RequestId>4442587FB7D0A2F9</RequestId>
      |</Error>""".stripMargin

  val AccessControlAllowOrigin = "Access-Control-Allow-Origin"
  val XAmzId = "x-amz-id-2"
  val ChunkedTransferEncoding = "chunked"

end UploadControllerSpec

object UrlHelper:
  def -/(uri: String) =
    if uri.startsWith("/") then uri.drop(1) else uri
