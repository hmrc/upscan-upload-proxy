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

package uk.gov.hmrc.upscanuploadproxy.parsers

import akka.stream.scaladsl.Sink
import play.api.libs.streams.Accumulator
import play.api.mvc.MultipartFormData.FilePart
import play.api.mvc.{BodyParser, MultipartFormData, PlayBodyParsers, Result}
import play.core.parsers.Multipart
import uk.gov.hmrc.upscanuploadproxy.helpers.Response

import scala.concurrent.ExecutionContext

object RedirectUrlParser {

  def parser(parser: PlayBodyParsers)(implicit ec: ExecutionContext): BodyParser[String] =
    BodyParser { requestHeader =>
      parser
        .multipartFormData(fileIgnoreHandler)(requestHeader)
        .map(_.right.map(extractErrorActionRedirect).joinRight)
    }

  private def fileIgnoreHandler(implicit ec: ExecutionContext): Multipart.FilePartHandler[Unit] =
    fileInfo =>
      Accumulator(Sink.ignore)
        .map(_ => FilePart(fileInfo.partName, fileInfo.fileName, fileInfo.contentType, ()))

  private def extractErrorActionRedirect(multiPartFormData: MultipartFormData[Unit]): Either[Result, String] =
    multiPartFormData.dataParts
      .get("error_action_redirect")
      .flatMap(_.headOption)
      .toRight(missingRedirectUrl)

  private val missingRedirectUrl = Response.badRequest("Could not find error_action_redirect field in request")

}
