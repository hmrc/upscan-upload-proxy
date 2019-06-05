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
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.ByteString
import cats.data.EitherT
import cats.implicits._
import javax.inject.{Inject, Singleton}
import play.api.mvc.{AbstractController, Action, BodyParser, ControllerComponents, Result}
import play.api.libs.streams.Accumulator
import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.upscanuploadproxy.UploadUriGenerator
import uk.gov.hmrc.upscanuploadproxy.helpers.Response
import uk.gov.hmrc.upscanuploadproxy.parsers.Multiparse
import uk.gov.hmrc.upscanuploadproxy.services.ProxyService


/** A Streamed variant of UploadController. Avoids copying the file to disk, and processing the multipart before entering the Action handler.
  * With a local target (here destination), it provides increased speeds of ~3 secs rather than ~8 secs with a 1GB file.
  * (Which is insignificant with the latency of uploads to Amazon)
  * However, for missing redirect field, it responds in < 0.1 sec rather than 4 sec.
  */
@Singleton()
class UploadControllerStreaming @Inject()(uriGenerator: UploadUriGenerator, proxyService: ProxyService, cc: ControllerComponents)(
  implicit ec : ExecutionContext
         , mat: Materializer
         )
    extends AbstractController(cc) {

  def upload(destination: String) =
    Action.async(rawParser) { implicit request =>
      val url = /* For testing */ "http://localhost:9000/destination" // uriGenerator.uri(destination)
      (for {
         boundary          <- EitherT.fromEither[Future](
                                request.headers.get("Content-Type")
                                  .flatMap(ct => scala.util.Try(ct.split("boundary=")(1)).toOption)
                                  .toRight(Response.badRequest("Missing boundary in headers"))
                              )
         (fResponse, fRedirectUrl) =  request.body
                                .alsoToMat(fileUpload(url, request.headers.headers))(Keep.right)
                                .toMat(Multiparse.extractMetaSink(boundary, "error_action_redirect"))(Keep.both)
                                .run()
         optRedirectUrl    <- EitherT.liftF(fRedirectUrl)
         redirectUrl       <- EitherT.fromEither[Future](optRedirectUrl
                                .toRight(Response.badRequest("Could not find error_action_redirect field in request"))
                              )
         response          <- EitherT(fResponse)
                                .leftMap(b => Response.redirect(redirectUrl, b))
       } yield response /* For testing */ .withHeaders("X-Redirect-Url" -> redirectUrl)
      ).merge
    }

  private def getRequiredHeaders(headers: Seq[(String, String)]): Seq[(String, String)] =
    headers.filter {
      case (header, _) => header.equalsIgnoreCase("content-type") || header.equalsIgnoreCase("content-length")
    }

  def fileUpload(url: String, headers: Seq[(String, String)]): Sink[ByteString, Future[Either[String, Result]]] =
   Sink
      .asPublisher[ByteString](fanout = false)
      .mapMaterializedValue(Source.fromPublisher)
      .mapMaterializedValue { source =>
        proxyService
          .post(url, source, getRequiredHeaders(headers)).value
      }

  def destination =
    Action.async(parse.raw) { request =>
      Future(Ok(s"request length was ${request.body.size}"))
    }

  // alternative to parser.raw which doesn't write to memory/disk, but allows streaming straight to a sink
  private def rawParser: BodyParser[Source[ByteString, _]] =
    BodyParser("rawParser") { _ =>
      Accumulator
        .source[ByteString]
	      .map(Right.apply)
  }
}
