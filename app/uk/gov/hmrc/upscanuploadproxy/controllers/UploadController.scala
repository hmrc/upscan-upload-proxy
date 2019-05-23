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

import akka.stream.scaladsl.{FileIO, Source}
import cats.data.EitherT
import cats.implicits._
import javax.inject.{Inject, Singleton}
import play.api.libs.Files
import play.api.mvc.MultipartFormData.DataPart
import play.api.mvc._
import uk.gov.hmrc.upscanuploadproxy.UploadUriGenerator
import uk.gov.hmrc.upscanuploadproxy.helper.Response
import uk.gov.hmrc.upscanuploadproxy.services.ProxyService

import scala.concurrent.{ExecutionContext, Future}

@Singleton()
class UploadController @Inject()(uriGenerator: UploadUriGenerator, proxyService: ProxyService, cc: ControllerComponents)(
  implicit ec: ExecutionContext)
    extends AbstractController(cc) {

  private val missingRedirectUrl = Response.badRequest("Could not find error_action_redirect field in request")

  private def getBodyParts(bodyParts: Map[String, Seq[String]]): List[DataPart] =
    (for {
      (name, values) <- bodyParts
      value          <- values
    } yield MultipartFormData.DataPart(name, value)).toList

  private def getFileParts(files: Seq[MultipartFormData.FilePart[Files.TemporaryFile]]) =
    files.map(filePart => filePart.copy(ref = FileIO.fromPath(filePart.ref.path)))

  def upload(destination: String): Action[MultipartFormData[Files.TemporaryFile]] =
    Action.async(parse.multipartFormData) { implicit request =>
      val url         = uriGenerator.uri(destination)
      val body        = request.body
      val redirectUrl = body.dataParts.get("error_action_redirect").map(_.head)

      val result = for {
        redirectUrl <- EitherT.fromEither[Future](redirectUrl.toRight(missingRedirectUrl))
        dataParts     = getBodyParts(body.dataParts)
        fileParts     = getFileParts(body.files)
        contentLength = request.headers.get("content-length").map(length => "Content-Length" -> length).toList
        response <- proxyService
                     .post(url, Source(dataParts ++ fileParts), contentLength)
                     .leftMap(Response.redirect(redirectUrl, _))

      } yield response

      result.merge
    }
}
