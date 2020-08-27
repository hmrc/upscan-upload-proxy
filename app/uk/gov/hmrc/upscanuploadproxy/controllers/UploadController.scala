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
import uk.gov.hmrc.upscanuploadproxy.helpers.{Response, XmlErrorResponse}
import uk.gov.hmrc.upscanuploadproxy.model.{ErrorAction, UploadRequest}
import uk.gov.hmrc.upscanuploadproxy.parsers.{CompositeBodyParser, ErrorActionParser, RawParser}
import uk.gov.hmrc.upscanuploadproxy.services.ProxyService
import uk.gov.hmrc.upscanuploadproxy.services.ProxyService.FailureResponse

import scala.concurrent.{ExecutionContext, Future}

@Singleton()
class UploadController @Inject()(uriGenerator: UploadUriGenerator, proxyService: ProxyService, cc: ControllerComponents)(
  implicit ec: ExecutionContext)
    extends AbstractController(cc) {

  /*
   * Note that the multipart-form is proxied straight through at present (without modification).
   * This means that the error_action_redirect field (if set) is forwarded onto AWS even though it is intended for us.
   *
   * Note that there is an inconsistency here in how response headers are handled.
   * A success result passes back any AWS response headers, whereas a failure result does not.
   *
   * If the BodyParser returns a Left[Result] then that will be returned directly to the client by the framework.
   * If it returns an UploadRequest that contains a Left[String] we treat that as an Internal Server Error for
   * consistency with the existing approach in `passThrough`, although this seems a questionable choice of status
   * code.
   */
  def upload(destination: String): Action[UploadRequest] = Action.async(uploadRequestParser) { implicit request =>
    val url = uriGenerator.uri(destination)
    val proxyHeaders = extractS3Headers(request.headers.headers)
    val failWith = handleFailure(request.body.errorAction) _

    request.body.errorOrMultipartForm.fold(
      bodyParseError => Future.successful(failWith(FailureResponse(INTERNAL_SERVER_ERROR, bodyParseError))),
      multipartForm  => proxyService.proxy(url, request.withHeaders(Headers(proxyHeaders: _*)), multipartForm, ProxyService.toResultEither).map {
        _.fold(
          failure => failWith(failure),
          success => success
        )
      }
    )
  }

  /*
   * If the client supplied an error action redirect url, redirect passing the error details as query params.
   * Otherwise send back the failure status code with a JSON body representing the error if possible.
   */
  private def handleFailure(errorAction: ErrorAction)(failure: FailureResponse): Result =
    errorAction.redirectUrl.fold(ifEmpty = Response.json(failure.statusCode, XmlErrorResponse.toJson(errorAction.key, failure.body))) { redirectUrl =>
      Response.redirect(redirectUrl, XmlErrorResponse.toFields(errorAction.key, failure.body))
    }

  def passThrough(destination: String): Action[Either[String, Source[ByteString, _]]] = Action.async(rawParser) { implicit request =>
    val url = uriGenerator.uri(destination)
    val proxyHeaders = extractS3Headers(request.headers.headers)
    request.body.fold(
      err           => Future.successful(Response.internalServerError(err)),
      multipartForm => proxyService.proxy(url, request.withHeaders(Headers(proxyHeaders: _*)), multipartForm, ProxyService.toResult)
    )
  }

  private val rawParser: BodyParser[Either[String, Source[ByteString, _]]] = RawParser.parser(cc.parsers)

  private val uploadRequestParser: BodyParser[UploadRequest] = CompositeBodyParser(
    ErrorActionParser.parser(cc.parsers), rawParser
  ).map(UploadRequest.tupled)

  private val s3Headers = Set("origin", "access-control-request-method", "content-type", "content-length")

  private def extractS3Headers(headers: Seq[(String, String)]): Seq[(String, String)] =
    headers.filter { case (header, _) => s3Headers.contains(header.toLowerCase) }
}
