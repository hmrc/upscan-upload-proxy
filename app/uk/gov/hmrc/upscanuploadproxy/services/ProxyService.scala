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

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.google.inject.Singleton
import javax.inject.Inject
import play.api.Logging
import play.api.http.Status
import play.api.libs.ws.{WSClient, WSResponse}
import play.api.mvc.{Request, Result, Results}

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ProxyService @Inject()(wsClient: WSClient)(implicit ec: ExecutionContext) extends Logging {

  def proxy[T](
    url: String,
    request: Request[_],
    source: Source[ByteString, _],
    processResponse: WSResponse => T): Future[T] = {

    logger.info(s"Request: Url: $url Headers: ${request.headers.headers}")

    wsClient
      .url(url)
      .withFollowRedirects(false)
      .withMethod(request.method)
      .withHttpHeaders(request.headers.headers: _*)
      .withQueryStringParameters(request.queryString.mapValues(_.head).toSeq: _*)
      .withRequestTimeout(Duration.Inf)
      .withBody(source)
      .execute(request.method)
      .map(logResponse)
      .map(processResponse)
  }

  private def logResponse(response: WSResponse): WSResponse = {
    logger.info(s"Response: Status ${response.status}, Body ${response.body}, Headers ${response.headers}")
    response
  }
}

object ProxyService {

  type SuccessResponse = Result
  case class FailureResponse(statusCode: Int, body: String)

  def toResultEither(response: WSResponse): Either[FailureResponse, SuccessResponse] =
    Either.cond(
      Status.isSuccessful(response.status) || Status.isRedirect(response.status),
      toResult(response),
      toFailureResponse(response))

  def toResult(response: WSResponse): Result = {
    val headers = response.headers.toList.flatMap { case (h, v) => v.map((h, _)) }
    Results.Status(response.status)(response.body).withHeaders(headers: _*)
  }

  private def toFailureResponse(response: WSResponse): FailureResponse =
    FailureResponse(response.status, response.body)
}
