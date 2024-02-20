/*
 * Copyright 2024 HM Revenue & Customs
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

import play.api.libs.Files.TemporaryFile
import play.api.mvc.{BodyParser, MultipartFormData, PlayBodyParsers}

import scala.concurrent.ExecutionContext

object OriginalFileNameParser {

  def parser(parsers: PlayBodyParsers)(implicit ec: ExecutionContext): BodyParser[Option[String]] = BodyParser { request =>
    val multipartFormDataParser: BodyParser[MultipartFormData[TemporaryFile]] = parsers.multipartFormData
    multipartFormDataParser(request).map {
      case Right(formData) =>
        formData.files.headOption match {
          case Some(filePart) => Right(Some(filePart.filename))
          case None => Right(Some(s"formData.files was empty ${formData.files.size} - bad parts count: ${formData.badParts.size} - bad parts content: ${formData.badParts.mkString("\n")}"))
        }
      case Left(result) => Left(result)
    }
  }

}
