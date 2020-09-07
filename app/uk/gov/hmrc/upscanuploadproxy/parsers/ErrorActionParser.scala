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

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8

import akka.stream.scaladsl.Sink
import org.apache.http.client.utils.URIBuilder
import play.api.libs.streams.Accumulator
import play.api.mvc.MultipartFormData.FilePart
import play.api.mvc.{BodyParser, MultipartFormData, PlayBodyParsers, Result}
import play.core.parsers.Multipart
import uk.gov.hmrc.upscanuploadproxy.helpers.Response
import uk.gov.hmrc.upscanuploadproxy.model.ErrorAction

import scala.concurrent.ExecutionContext
import scala.util.Try

/*
 * This is a conventional BodyParser implementation that fails parsing with a Left[Result].
 * This will be handled by the framework, with the implication that the result will be sent directly to the client.
 * This is OK here because if we fail to parse and construct the error redirect URL we do not have the option to
 * redirect the error anyway!
 */
object ErrorActionParser {

  def parser(parser: PlayBodyParsers)(implicit ec: ExecutionContext): BodyParser[ErrorAction] =
    BodyParser { requestHeader =>
      parser.multipartFormData(fileIgnoreHandler)(requestHeader).map { resultOrMultipartForm =>
        resultOrMultipartForm.flatMap(extractErrorAction)
      }
    }

  private def fileIgnoreHandler(implicit ec: ExecutionContext): Multipart.FilePartHandler[Unit] =
    fileInfo =>
      Accumulator(Sink.ignore)
        .map(_ => FilePart(fileInfo.partName, fileInfo.fileName, fileInfo.contentType, ()))

  private def extractErrorAction(multipartFormData: MultipartFormData[Unit]): Either[Result, ErrorAction] = {
    val maybeErrorActionRedirect = extractErrorActionRedirect(multipartFormData)
    extractKey(multipartFormData).flatMap { key =>
      maybeErrorActionRedirect
        .map { errorActionRedirect =>
          validateErrorActionRedirectUrlWithKey(errorActionRedirect, key).map(_ =>
            ErrorAction(maybeErrorActionRedirect, key))
        }
        .getOrElse(Right(ErrorAction(maybeErrorActionRedirect, key)))
    }
  }

  private def extractErrorActionRedirect(multipartFormData: MultipartFormData[Unit]): Option[String] =
    extractSingletonFormValue("error_action_redirect", multipartFormData)

  private def extractKey(multiPartFormData: MultipartFormData[Unit]): Either[Result, String] =
    extractSingletonFormValue("key", multiPartFormData).toRight(left = missingKey)

  private def extractSingletonFormValue(key: String, multiPartFormData: MultipartFormData[Unit]): Option[String] =
    multiPartFormData.dataParts
      .get(key)
      .flatMap(_.headOption)

  private def validateErrorActionRedirectUrlWithKey(redirectUrl: String, key: String): Either[Result, URI] =
    Try {
      new URIBuilder(redirectUrl, UTF_8).addParameter("key", key).build()
    }.toOption.toRight(left = badRedirectUrl)

  private val missingKey     = Response.badRequest("Could not find key field in request")
  private val badRedirectUrl = Response.badRequest("Unable to build valid redirect URL for error action")
}
