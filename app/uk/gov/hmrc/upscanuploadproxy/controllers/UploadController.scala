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

import akka.stream.scaladsl.{FileIO, Sink}
import cats.data.EitherT
import cats.implicits._
import javax.inject.{Inject, Singleton}
import play.api.libs.streams.Accumulator
import play.api.mvc.MultipartFormData.FilePart
import play.api.mvc._
import play.core.parsers.Multipart
import play.core.parsers.Multipart.FileInfo
import uk.gov.hmrc.upscanuploadproxy.UploadUriGenerator
import uk.gov.hmrc.upscanuploadproxy.helpers.Response
import uk.gov.hmrc.upscanuploadproxy.parsers.CompositeBodyParser
import uk.gov.hmrc.upscanuploadproxy.services.ProxyService

import scala.concurrent.{ExecutionContext, Future}

@Singleton()
class UploadController @Inject()(uriGenerator: UploadUriGenerator, proxyService: ProxyService, cc: ControllerComponents)(
  implicit ec: ExecutionContext)
    extends AbstractController(cc) {

  private val missingRedirectUrl = Response.badRequest("Could not find error_action_redirect field in request")

  private val compositeBodyParser = CompositeBodyParser(parse.raw, parse.multipartFormData(fileIgnoreHandler()))

  private def getRequiredHeaders(headers: Seq[(String, String)]): Seq[(String, String)] = headers.filter {
    case (header, _) => header.equalsIgnoreCase("content-type") || header.equalsIgnoreCase("content-length")
  }

  private def fileIgnoreHandler(): Multipart.FilePartHandler[Unit] = {
    case FileInfo(partName, filename, contentType) =>
      Accumulator(Sink.ignore).mapFuture(_ => Future.successful(FilePart(partName, filename, contentType, ())))
  }

  def upload(destination: String): Action[(RawBuffer, MultipartFormData[Unit])] =
    Action.async(compositeBodyParser) { implicit request =>
      val url              = uriGenerator.uri(destination)
      val (raw, multipart) = request.body
      val headers          = request.headers.headers
      val redirectUrl      = multipart.dataParts.get("error_action_redirect").map(_.head)

      (for {
        redirectUrl <- EitherT.fromEither[Future](redirectUrl.toRight(missingRedirectUrl))
        response <- proxyService
                     .post(url, FileIO.fromPath(raw.asFile.toPath), getRequiredHeaders(headers))
                     .leftMap(Response.redirect(redirectUrl, _))

      } yield response).merge
    }
}
