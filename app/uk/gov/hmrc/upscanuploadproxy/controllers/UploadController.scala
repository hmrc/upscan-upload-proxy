/*
 * Copyright 2022 HM Revenue & Customs
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

import com.typesafe.config.Config
import play.api.Logger
import play.api.libs.Files.TemporaryFile
import play.api.libs.ws.WSResponse
import play.api.mvc._
import uk.gov.hmrc.crypto.{PlainText, SymmetricCryptoFactory}
import uk.gov.hmrc.upscanuploadproxy.UploadUriGenerator
import uk.gov.hmrc.upscanuploadproxy.helpers.Logging.withFileReferenceContext
import uk.gov.hmrc.upscanuploadproxy.helpers.{BufferedBody, Response, XmlErrorResponse}
import uk.gov.hmrc.upscanuploadproxy.model.{ErrorAction, UploadRequest}
import uk.gov.hmrc.upscanuploadproxy.parsers.{CompositeBodyParser, ErrorActionParser, OriginalFileNameParser}
import uk.gov.hmrc.upscanuploadproxy.services.ProxyService
import uk.gov.hmrc.upscanuploadproxy.services.ProxyService.FailureResponse

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.{Try, Using}

@Singleton()
class UploadController @Inject()(
  uriGenerator: UploadUriGenerator,
  proxyService: ProxyService,
  config      : Config,
  cc          : ControllerComponents
)(implicit
  ec: ExecutionContext
) extends AbstractController(cc) {

  private val logger = Logger(this.getClass)

  private val piiCrypto = SymmetricCryptoFactory.aesCryptoFromConfig("pii.encryption", config)

  def upload(destination: String): Action[UploadRequest] = Action.async(uploadRequestParser) { implicit request =>
    val url          = uriGenerator.uri(destination)
    val proxyHeaders = extractS3Headers(request.headers.headers)
    val errorAction  = request.body.errorAction
    val failWith     = handleFailure(errorAction) _

    // BDOG-3029 logging
    if(request.body.originalFileName.isEmpty) {
      val maybeBoundary = for {
        mt         <- request.mediaType
        (_, value) <- mt.parameters.find(_._1.equalsIgnoreCase("boundary"))
        boundary   <- value
      } yield boundary

      maybeBoundary.fold(
        logger.info(s"Boundary header missing for key [${errorAction.key}]")
      ){ boundary =>
        val encryptedFile = encryptForLogging(sanitiseFile(request.body.bufferedBody, boundary))

        logger.info(s"Unable to extract original file name for key [${errorAction.key}] - encrypted request: $encryptedFile")
      }
    }

    def logResponse(response: WSResponse): WSResponse =
      withFileReferenceContext(errorAction.key) {
        logger.info(s"Upload response - Key=[${errorAction.key}] Status=[${response.status}] Body=[${response.body}] Headers=[${response.headers}]")
        response
      }

    BufferedBody.withTemporaryFile(request.map(_.bufferedBody), fileReference = Some(errorAction.key)) {
      _.fold(
        err => {
          withFileReferenceContext(errorAction.key) {
            logger.error(s"TemporaryFile Error [${err.getMessage}]", err)
          }
          Future.successful(failWith(FailureResponse(INTERNAL_SERVER_ERROR, XmlErrorResponse.toXmlErrorBody("Upload Error"))))
        },
        body => {
          withFileReferenceContext(errorAction.key) {
            logger.info(s"Upload request - Key=[${errorAction.key}] Url=[$url] Headers=[$proxyHeaders]")
          }
          proxyService
            .proxy(url, request.withHeaders(Headers(proxyHeaders: _*)), body, (logResponse _).andThen(ProxyService.toResultEither))
            .map {
              _.fold(
                failure => failWith(failure),
                success => success
              )
            }
        }
      )
    }
  }

  /*
   * If the client supplied an error action redirect url, redirect passing the error details as query params.
   * Otherwise send back the failure status code with a JSON body representing the error if possible.
   */
  private def handleFailure(errorAction: ErrorAction)(failure: FailureResponse): Result = {
    lazy val xmlFields = XmlErrorResponse.toFields(errorAction.key, failure.body)
    lazy val json = Response.json(failure.statusCode, body = XmlErrorResponse.toJson(errorAction.key, failure.body))
    val result = errorAction.redirectUrl match {
      case None => json
      case Some(_) if isErrorRedirectUrlPolicyError(xmlFields, failure.statusCode) => json
      case Some(errorRedirectUrl) => Response.redirect(errorRedirectUrl, queryParams = xmlFields)
    }
    result.withHeaders(failure.headers: _*)
  }

  private def isErrorRedirectUrlPolicyError(xmlFields: Seq[(String, String)], failureStatusCode: Int): Boolean = {
    lazy val isForbiddenStatusCode = failureStatusCode == FORBIDDEN
    lazy val isAccessDeniedCode    = xmlFields.find(_._1 == "errorCode").exists(_._2 == "AccessDenied")
    lazy val isErrorRedirectPolicyErrorMessage = xmlFields
      .find(_._1 == "errorMessage")
      .map(_._2)
      .exists(m => m.contains("Policy Condition failed") && m.contains("$error_action_redirect"))
    isForbiddenStatusCode && isAccessDeniedCode && isErrorRedirectPolicyErrorMessage
  }

  def passThrough(destination: String): Action[TemporaryFile] = Action.async(cc.parsers.temporaryFile) { implicit request =>
    val url          = uriGenerator.uri(destination)
    val proxyHeaders = extractS3Headers(request.headers.headers)

    def logResponse(response: WSResponse): WSResponse = {
      logger.info(s"PassThrough response - Status=[${response.status}] Body=[${response.body}] Headers=[${response.headers}]")
      response
    }

    BufferedBody.withTemporaryFile(request, fileReference = None) {
      _.fold(
        err => {
          logger.error(s"TemporaryFile Error [${err.getMessage}]", err)
          Future.successful(Response.internalServerError("Upload Error"))
        },
        body => {
          logger.info(s"PassThrough request - Url=[$url] Headers=[$proxyHeaders]")
          proxyService.proxy(url, request.withHeaders(Headers(proxyHeaders: _*)), body, (logResponse _).andThen(ProxyService.toResult))
        }
      )
    }
  }

  private val uploadRequestParser: BodyParser[UploadRequest] = CompositeBodyParser(
    ErrorActionParser.parser(cc.parsers),
    cc.parsers.temporaryFile,
    OriginalFileNameParser.parser(cc.parsers)
  ).map(UploadRequest.tupled)

  private val s3Headers = Set("origin", "access-control-request-method", "content-type", "content-length")

  private def extractS3Headers(headers: Seq[(String, String)]): Seq[(String, String)] =
    headers.filter { case (header, _) => s3Headers.contains(header.toLowerCase) }

  private def encryptForLogging(in: Try[String]): Try[String] =
    in.map { text =>
      piiCrypto.encrypt(PlainText(text)).value
    }

  private def sanitiseFile(file: TemporaryFile, boundary: String): Try[String] =
    Using(Source.fromFile(file.path.toFile, "UTF-8")) { source =>
      val fileContent = source.mkString
      fileContent.split(s"--$boundary").map(_.take(300) + "\n").mkString(s"--$boundary")
    }
}
