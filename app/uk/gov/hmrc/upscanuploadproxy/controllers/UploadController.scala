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

import akka.stream.scaladsl.Source
import akka.util.ByteString
import javax.inject.{Inject, Singleton}
import play.api.mvc._
import uk.gov.hmrc.upscanuploadproxy.UploadUriGenerator
import uk.gov.hmrc.upscanuploadproxy.helpers.Response
import uk.gov.hmrc.upscanuploadproxy.model.UploadRequest
import uk.gov.hmrc.upscanuploadproxy.parsers.{CompositeBodyParser, RawParser, RedirectUrlWithKeyParser}
import uk.gov.hmrc.upscanuploadproxy.services.ProxyService

import scala.concurrent.{ExecutionContext, Future}

@Singleton()
class UploadController @Inject()(uriGenerator: UploadUriGenerator, proxyService: ProxyService, cc: ControllerComponents)(
  implicit ec: ExecutionContext)
    extends AbstractController(cc) {

  def upload(destination: String): Action[UploadRequest] = Action.async(uploadRequestParser) { implicit request =>
    val url          = uriGenerator.uri(destination)
    val proxyHeaders = extractS3Headers(request.headers.headers)
    request.body.file.map(file => proxyService.proxy(url, request.withHeaders(Headers(proxyHeaders: _*)), file, ProxyService.toResultEither))
      .fold(
        err => Future.successful(InternalServerError(err)),
        body => body.map {
          case Right(result) => result
          case Left(err)     =>  Response.redirect(request.body.redirectUrl, err)
        })
  }

  def proxyOptions(destination: String) = Action.async(rawParser) { implicit request =>
    val url          = uriGenerator.uri(destination)
    val proxyHeaders = extractS3Headers(request.headers.headers)
    request.body.fold(
      err => Future.successful(Response.internalServerError(err)),
      body => proxyService.proxy(url, request.withHeaders(Headers(proxyHeaders: _*)), body, ProxyService.toResult)
    )
  }

  private val rawParser: BodyParser[Either[String, Source[ByteString, _]]] = RawParser.parser(cc.parsers)

  private val uploadRequestParser =
    CompositeBodyParser(RedirectUrlWithKeyParser.parser(cc.parsers), rawParser).map(UploadRequest.tupled)

  private val s3Headers = Set("origin", "access-control-request-method", "content-type", "content-length")

  private def extractS3Headers(headers: Seq[(String, String)]): Seq[(String, String)] =
    headers.filter { case (header, _) => s3Headers.contains(header.toLowerCase) }
}
