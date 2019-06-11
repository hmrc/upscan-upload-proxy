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

package uk.gov.hmrc.upscanuploadproxy.controllers

import akka.stream.scaladsl.Source
import akka.util.ByteString
import javax.inject.{Inject, Singleton}
import play.api.mvc._
import uk.gov.hmrc.upscanuploadproxy.UploadUriGenerator
import uk.gov.hmrc.upscanuploadproxy.helpers.Response
import uk.gov.hmrc.upscanuploadproxy.model.UploadRequest
import uk.gov.hmrc.upscanuploadproxy.parsers.{CompositeBodyParser, RawParser, RedirectUrlParser}
import uk.gov.hmrc.upscanuploadproxy.services.ProxyService

import scala.concurrent.ExecutionContext

@Singleton()
class UploadController @Inject()(uriGenerator: UploadUriGenerator, proxyService: ProxyService, cc: ControllerComponents)(
  implicit ec: ExecutionContext)
    extends AbstractController(cc) {

  def upload(destination: String): Action[UploadRequest] = Action.async(uploadRequestParser) { implicit request =>
    val url          = uriGenerator.uri(destination)
    val proxyHeaders = extractS3Headers(request.headers.headers)

    val response = proxyService
      .proxy(url, request.withHeaders(Headers(proxyHeaders: _*)), request.body.file, ProxyService.toResultEither)

    response.map {
      case Right(result)      => result
      case Left(errorMessage) => Response.redirect(request.body.redirectUrl, errorMessage)
    }
  }

  def proxy(destination: String): Action[Source[ByteString, _]] = Action.async(rawParser) { implicit request =>
    val url          = uriGenerator.uri(destination)
    val proxyHeaders = extractS3Headers(request.headers.headers)
    proxyService
      .proxy(url, request.withHeaders(Headers(proxyHeaders: _*)), request.body, ProxyService.toResult)
  }

  private val rawParser = RawParser.parser(cc.parsers)

  private val uploadRequestParser =
    CompositeBodyParser(RedirectUrlParser.parser(cc.parsers), rawParser).map(UploadRequest.tupled)

  private val s3Headers = Set("origin", "access-control-request-method", "content-type", "content-length")

  private def extractS3Headers(headers: Seq[(String, String)]): Seq[(String, String)] =
    headers.filter { case (header, _) => s3Headers.contains(header.toLowerCase) }
}
