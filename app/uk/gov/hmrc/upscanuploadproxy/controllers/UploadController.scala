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
import play.api.mvc.Request
import play.api.mvc.Result
import play.api.mvc.BodyParsers
import play.api.mvc.DefaultPlayBodyParsers
import play.api.http.HttpErrorHandler
import akka.stream.scaladsl.Flow
import play.api.mvc.RequestHeader
import akka.stream.Materializer

@Singleton()
class UploadController @Inject()(uriGenerator: UploadUriGenerator, proxyService: ProxyService, cc: ControllerComponents, x: DefaultPlayBodyParsers)(
  implicit ec: ExecutionContext)
    extends AbstractController(cc) {

  private val missingRedirectUrl =
    Response.badRequest("Could not find error_action_redirect field in request")

  private def getRequiredHeaders(headers: Seq[(String, String)]): Seq[(String, String)] =
    headers.filter {
      case (header, _) => header.equalsIgnoreCase("content-type") || header.equalsIgnoreCase("content-length")
    }


  def upload(destination: String): Action[MultipartFormData[Unit]] =
  //   Action.async(SplitBodyParser(rawParser, parse.multipartFormData(fileIgnoreHandler))) { implicit request =>
    Action.async(parse.multipartFormData(fileIgnoreHandler)) { implicit request =>
      val url              = uriGenerator.uri(destination)
      println(s"url=$url")
  //     val (raw, multipart) = request.body
      val multipart = request.body
      // println(s"raw=$raw")
      println(s"multipart=$multipart")
      val headers          = request.headers.headers
      val redirectUrl      = multipart.dataParts.get("error_action_redirect").map(_.head)
      println(s"redirectUrl=$redirectUrl")

      (for {
         redirectUrl <- EitherT.fromEither[Future](redirectUrl.toRight(missingRedirectUrl))
         response    <- { println(s"forwarding request")
                          /*proxyService
                            .post(url, raw, getRequiredHeaders(headers))
                            .leftMap(Response.redirect(redirectUrl, _))*/
                            EitherT.pure[Future, Result](BadRequest("Skipping call"))
                        }
       } yield response
      ).merge
    }

  // def upload(destination: String): Action[(Source[ByteString, _], MultipartFormData[Unit])] =
  //   Action.async(SplitBodyParser(rawParser, parse.multipartFormData(fileIgnoreHandler))) { implicit request =>
  //     val url              = uriGenerator.uri(destination)
  //     println(s"url=$url")
  //     val (raw, multipart) = request.body
  //     println(s"raw=$raw")
  //     println(s"multipart=$multipart")
  //     val headers          = request.headers.headers
  //     val redirectUrl      = multipart.dataParts.get("error_action_redirect").map(_.head)
  //     println(s"redirectUrl=$redirectUrl")

  //     (for {
  //        redirectUrl <- EitherT.fromEither[Future](redirectUrl.toRight(missingRedirectUrl))
  //        response    <- { println(s"forwarding request")
  //                         proxyService
  //                           .post(url, raw, getRequiredHeaders(headers))
  //                           .leftMap(Response.redirect(redirectUrl, _))
  //                       }
  //      } yield response
  //     ).merge
  //   }

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


  def multipartFormData[A](filePartHandler: Multipart.FilePartHandler[A])(implicit mat: Materializer): BodyParser[MultipartFormData[A]] = {
    BodyParser("multipartFormData") { request =>
      multipartParser(x.DefaultMaxTextLength, filePartHandler, x.errorHandler).apply(request)
    }
  }



  /**
   * Parses the request body into a Multipart body.
   *
   * @param maxMemoryBufferSize The maximum amount of data to parse into memory.
   * @param filePartHandler The accumulator to handle the file parts.
   */
  def multipartParser[A](maxMemoryBufferSize: Int, filePartHandler: Multipart.FilePartHandler[A], errorHandler: HttpErrorHandler)(implicit mat: Materializer): BodyParser[MultipartFormData[A]] = BodyParser { request =>
    Multipart.partParser(maxMemoryBufferSize, errorHandler) {
      val handleFileParts = Flow[MultipartFormData.Part[Source[ByteString, _]]].mapAsync(1) {
        case filePart: MultipartFormData.FilePart[Source[ByteString, _]] =>
          filePartHandler(Multipart.FileInfo(filePart.key, filePart.filename, filePart.contentType)).run(filePart.ref)
        case other: MultipartFormData.Part[_] => Future.successful(other.asInstanceOf[MultipartFormData.Part[Nothing]])
      }

      val multipartAccumulator = Accumulator(Sink.fold[Seq[MultipartFormData.Part[A]], MultipartFormData.Part[A]](Vector.empty)(_ :+ _)).mapFuture { parts =>

        def parseError = parts.collectFirst {
          case MultipartFormData.ParseError(msg) => createBadResult(msg, errorHandler = errorHandler)(request)
        }

        def bufferExceededError = parts.collectFirst {
          case MultipartFormData.MaxMemoryBufferExceeded(msg) => createBadResult(msg, REQUEST_ENTITY_TOO_LARGE, errorHandler)(request)
        }

        parseError orElse bufferExceededError getOrElse {
          Future.successful(Right(MultipartFormData(
            parts
              .collect {
                case dp: MultipartFormData.DataPart => dp
              }.groupBy(_.key)
              .map {
                case (key, partValues) => key -> partValues.map(_.value)
              },
            parts.collect {
              case fp: MultipartFormData.FilePart[A] => fp
            },
            parts.collect {
              case bad: MultipartFormData.BadPart => bad
            }
          )))
        }

      }

      multipartAccumulator.through(handleFileParts)
    }.apply(request)
  }

  private def createBadResult[A](msg: String, status: Int = BAD_REQUEST, errorHandler: HttpErrorHandler): RequestHeader => Future[Either[Result, A]] = { request =>
    errorHandler.onClientError(request, status, msg).map(Left(_))
  }
}
