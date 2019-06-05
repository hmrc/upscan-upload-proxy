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

  private val parser =
    CompositeBodyParser(RedirectUrlParser.parser(cc.parsers), RawParser.parser(cc.parsers)).map(UploadRequest.tupled)

  def upload(destination: String): Action[UploadRequest] = Action.async(parser) { implicit request =>
    val url          = uriGenerator.uri(destination)
    val proxyHeaders = extractS3Headers(request.headers.headers)

    proxyService
      .post(url, request.body.file, proxyHeaders)
      .map {
        case Right(result)      => result
        case Left(errorMessage) => Response.redirect(request.body.redirectUrl, errorMessage)
      }
  }

  private def extractS3Headers(headers: Seq[(String, String)]): Seq[(String, String)] =
    headers.filter {
      case (header, _) =>
        header.equalsIgnoreCase("content-type") || header.equalsIgnoreCase("content-length")
    }
}
