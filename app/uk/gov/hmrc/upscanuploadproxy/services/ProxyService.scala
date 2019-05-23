/*
 * Copyright 2019 HM Revenue & Customs
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

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import com.google.inject.Singleton
import javax.inject.Inject
import org.slf4j.LoggerFactory
import play.api.http.Status
import play.api.libs.ws.WSClient
import play.api.mvc.{MultipartFormData, Result, Results}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration

@Singleton
class ProxyService @Inject()(wsClient: WSClient)(implicit m: Materializer) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  type SuccessResponse = Result
  type FailureResponse = String
  def post(
    url: String,
    source: Source[MultipartFormData.Part[Source[ByteString, _]], _],
    headers: Seq[(String, String)]
  ): EitherT[Future, FailureResponse, SuccessResponse] = {

    logger.info(s"Url :$url}")

    val response = wsClient
      .url(url)
      .withRequestTimeout(Duration.Inf)
      .withFollowRedirects(false)
      .withHttpHeaders(headers: _*)
      .post(source)

    EitherT(response.map { r =>
      val headers = r.headers.toList.flatMap { case (h, v) => v.map((h, _)) }
      Either.cond(
        Status.isSuccessful(r.status) || Status.isRedirect(r.status),
        Results.Status(r.status)(r.body).withHeaders(headers: _*),
        r.body)
    })
  }
}
