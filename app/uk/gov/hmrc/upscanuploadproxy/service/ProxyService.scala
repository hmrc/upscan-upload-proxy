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

import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import com.google.inject.Singleton
import play.api.http.Status
import play.api.libs.ws.{WSClient, WSResponse, writableOf_Source}
import play.api.mvc.{Request, Result, Results}
import uk.gov.hmrc.play.http.logging.Mdc

import javax.inject.Inject
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ProxyService @Inject()(wsClient: WSClient)(using ExecutionContext):

  def proxy[T](
    url            : String,
    request        : Request[_],
    source         : Source[ByteString, _],
    processResponse: WSResponse => T
  ): Future[T] =
    Mdc.preservingMdc:
      wsClient
        .url(url)
        .withFollowRedirects(false)
        .withMethod(request.method)
        .withHttpHeaders(request.headers.headers: _*)
        .withQueryStringParameters(request.queryString.view.mapValues(_.head).toSeq: _*)
        .withRequestTimeout(Duration.Inf)
        .withBody(source)
        .execute(request.method)
        .map(processResponse)

/*
 * An AWS Success response has no body (it will be either a 204 or 303) and so any response headers can be safely
 * passed on as we proxy the response.  An AWS Error response does have a body, which we will either omit or translate
 * as we proxy the response.  Consequently, we cannot simply forward all response headers in the Error case.  Instead,
 * we implement an 'allow list' here, and only forward CORS-related headers or custom Amazon headers.
 */
object ProxyService:

  type SuccessResponse = Result

  case class FailureResponse(
    statusCode: Int,
    body      : String,
    headers   : Seq[(String, String)] = Seq.empty
  )

  def toResultEither(response: WSResponse): Either[FailureResponse, SuccessResponse] =
    Either.cond(
      Status.isSuccessful(response.status) || Status.isRedirect(response.status),
      toResult(response),
      toFailureResponse(response)
    )

  def toResult(response: WSResponse): Result =
    Results.Status(response.status)(response.body)
      .withHeaders(headersFrom(response): _*)

  private def toFailureResponse(response: WSResponse): FailureResponse =
    val exposableHeaders = headersFrom(response).filter((name, _) => isCorsResponseHeader(name) || isAmazonHeader(name))
    FailureResponse(response.status, response.body, exposableHeaders)

  private def headersFrom(response: WSResponse): Seq[(String, String)] =
    response.headers.toSeq.flatMap((h, v) => v.map((h, _)))

  // CORS response headers are defined at: https://fetch.spec.whatwg.org/#http-responses
  private def isCorsResponseHeader(name: String): Boolean =
    name.toLowerCase.startsWith("access-control-")

  private def isAmazonHeader(name: String): Boolean =
    name.toLowerCase.startsWith("x-amz-")
