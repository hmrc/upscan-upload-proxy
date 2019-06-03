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

import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import cats.data.EitherT
import cats.implicits._
import javax.inject.{Inject, Singleton}
import play.api.mvc.{AbstractController, Action, BodyParser, ControllerComponents, MultipartFormData}
import play.core.parsers.Multipart
import play.api.libs.streams.Accumulator
import uk.gov.hmrc.upscanuploadproxy.UploadUriGenerator
import uk.gov.hmrc.upscanuploadproxy.helpers.Response
import uk.gov.hmrc.upscanuploadproxy.parsers.SplitBodyParser
import uk.gov.hmrc.upscanuploadproxy.services.ProxyService

import scala.concurrent.{ExecutionContext, Future}

@Singleton()
class UploadController @Inject()(uriGenerator: UploadUriGenerator, proxyService: ProxyService, cc: ControllerComponents)(
  implicit ec: ExecutionContext)
    extends AbstractController(cc) {

  private val missingRedirectUrl = Response.badRequest("Could not find error_action_redirect field in request")

  private def getRequiredHeaders(headers: Seq[(String, String)]): Seq[(String, String)] = headers.filter {
    case (header, _) => header.equalsIgnoreCase("content-type") || header.equalsIgnoreCase("content-length")
  }


  def upload(destination: String): Action[(Source[ByteString, _], MultipartFormData[Unit])] =
    Action.async(SplitBodyParser(rawParser, parse.multipartFormData(fileIgnoreHandler))) { implicit request =>
      val url              = uriGenerator.uri(destination)
      val (raw, multipart) = request.body
      val headers          = request.headers.headers
      val redirectUrl      = multipart.dataParts.get("error_action_redirect").map(_.head)

      (for {
         redirectUrl <- EitherT.fromEither[Future](redirectUrl.toRight(missingRedirectUrl))
         response    <- proxyService
                          .post(url, raw, getRequiredHeaders(headers))
                          .leftMap(Response.redirect(redirectUrl, _))
       } yield response
      ).merge
    }

  // alternative to parser.raw which doesn't write to memory/disk, but allows streaming straight to a sink
  private def rawParser: BodyParser[Source[ByteString, _]] =
    BodyParser("rawParser") { _ =>
      Accumulator
        .source[ByteString]
	      .map(Right.apply)
  }

  // skip file parts
  private def fileIgnoreHandler: Multipart.FilePartHandler[Unit] = {
    case Multipart.FileInfo(partName, filename, contentType) =>
      Accumulator(Sink.ignore).mapFuture(_ => Future.successful(MultipartFormData.FilePart(partName, filename, contentType, ())))
  }
}
