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

import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Sink}
import cats.data.EitherT
import cats.implicits._
import javax.inject.{Inject, Singleton}
import play.api.libs.streams.Accumulator
import play.api.mvc.{AbstractController, Action, ControllerComponents, DefaultPlayBodyParsers, MultipartFormData, Request, Result}
import play.core.parsers.Multipart
import uk.gov.hmrc.upscanuploadproxy.UploadUriGenerator
import uk.gov.hmrc.upscanuploadproxy.helpers.Response
import uk.gov.hmrc.upscanuploadproxy.parsers.CompositeBodyParser
import uk.gov.hmrc.upscanuploadproxy.services.ProxyService
import scala.concurrent.{ExecutionContext, Future}

@Singleton()
class UploadController @Inject()(
    uriGenerator: UploadUriGenerator
  , proxyService: ProxyService
  , cc          : ControllerComponents
  , x           : DefaultPlayBodyParsers
  )(implicit ec : ExecutionContext
           , mat: Materializer
           )
    extends AbstractController(cc) {

  def upload(destination: String) =
  Action.async(CompositeBodyParser(parse.raw, parse.multipartFormData(fileIgnoreHandler))) { implicit request =>
      val url = /* For testing */ "http://localhost:9000/destination" // uriGenerator.uri(destination)
      val (raw, multipart) = request.body
      (for {
         redirectUrl <- EitherT.fromEither[Future](
                          multipart.dataParts.get("error_action_redirect").map(_.head)
                            .toRight(Response.badRequest("Could not find error_action_redirect field in request"))
                        )
         response    <- proxyService
                          .post(url, FileIO.fromPath(raw.asFile.toPath), getRequiredHeaders(request.headers.headers))
                          .leftMap(Response.redirect(redirectUrl, _))
       } yield response /* For testing */ .withHeaders("X-Redirect-Url" -> redirectUrl)
      ).merge
    }

  private def getRequiredHeaders(headers: Seq[(String, String)]): Seq[(String, String)] =
    headers.filter {
      case (header, _) => header.equalsIgnoreCase("content-type") || header.equalsIgnoreCase("content-length")
    }

  // skip file parts
  private def fileIgnoreHandler: Multipart.FilePartHandler[Unit] = {
    case Multipart.FileInfo(partName, filename, contentType) =>
      Accumulator(Sink.ignore).mapFuture(_ => Future.successful(MultipartFormData.FilePart(partName, filename, contentType, ())))
  }
}
