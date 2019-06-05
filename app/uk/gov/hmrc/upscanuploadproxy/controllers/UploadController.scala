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

import akka.stream.scaladsl.{Flow, Framing, Sink, Source}
import akka.util.ByteString
import cats.data.EitherT
import cats.implicits._
import javax.inject.{Inject, Singleton}
import play.api.mvc.{AbstractController, Action, BodyParser, ControllerComponents, MultipartFormData}
import play.core.parsers.Multipart
import play.api.libs.streams.Accumulator
import uk.gov.hmrc.upscanuploadproxy.UploadUriGenerator
import uk.gov.hmrc.upscanuploadproxy.helpers.Response
import uk.gov.hmrc.upscanuploadproxy.parsers.CompositeBodyParser
import uk.gov.hmrc.upscanuploadproxy.services.ProxyService

import scala.concurrent.{ExecutionContext, Future}
import org.apache.xml.serialize.LineSeparator
import akka.{Done, NotUsed}
import akka.stream.Materializer
import akka.stream.SinkShape
import akka.stream.scaladsl.GraphDSL.Implicits._
import akka.stream.scaladsl.{Broadcast, GraphDSL, Sink}
import akka.util.ByteString
import cats.data.EitherT
import cats.implicits._
import play.api.libs.streams.Accumulator
import play.api.mvc._
import akka.stream.scaladsl.Keep


@Singleton()
class UploadController @Inject()(uriGenerator: UploadUriGenerator, proxyService: ProxyService, cc: ControllerComponents)(
  implicit ec : ExecutionContext
         , mat: Materializer
         )
    extends AbstractController(cc) {

  private def getRequiredHeaders(headers: Seq[(String, String)]): Seq[(String, String)] =
    headers.filter {
      case (header, _) => header.equalsIgnoreCase("content-type") || header.equalsIgnoreCase("content-length")
    }


  def upload(destination: String) =
    Action.async(rawParser) { implicit request =>
      val url = "http://localhost:9000/destination" // uriGenerator.uri(destination)
      (for {
         boundary          <- EitherT.fromEither[Future](
                                request.headers.get("Content-Type")
                                  .flatMap(ct => scala.util.Try(ct.split("boundary=")(1)).toOption)
                                  .toRight(Response.badRequest("Missing boundary in headers"))
                              )
         (fResponse, fCtx) =  request.body
                                .alsoToMat(fileUpload(url, request.headers.headers))(Keep.right)
                                .toMat(redirectUrlExtractorSink(boundary))(Keep.both)
                                .run()
         ctx               <- EitherT.liftF(fCtx)
         redirectUrl       <- EitherT.fromEither[Future](ctx.redirectUrl
                                .toRight(Response.badRequest("Could not find error_action_redirect field in request"))
                              )
         response          <- EitherT(fResponse)
                                .leftMap(b => Response.redirect(redirectUrl, b))
       } yield response
      ).merge
    }

  def toLines(v: Seq[ByteString]): Seq[String] =
    v.map(_.utf8String).mkString.split(LineSeparator.Windows).toList

  trait ReadingState
  object ReadingState {
    case object WaitingForRedirect  extends ReadingState
    case object WaitingForBlankLine extends ReadingState
    case object ReadingRedirect     extends ReadingState
    case object Ignore              extends ReadingState
  }

  case class Context(state: ReadingState, redirectUrl: Option[String])

  class NoRedirectUrlException extends RuntimeException


  def redirectUrlExtractorSink(boundary: String): Sink[ByteString, Future[Context]] =
    Flow[ByteString]
      .sliding(2) // as long as two chunk size (default 131072?) contain a full line?
      .fold[Context](Context(ReadingState.WaitingForRedirect, None)){ (ctx, v) =>
        // optimised to avoid converting to lines once we've found the redirect
        if (ctx.state == ReadingState.Ignore) ctx
        else
          toLines(v).foldLeft(ctx){ (ctx, l) =>
            ctx.state match {
              case ReadingState.WaitingForRedirect if l == "Content-Disposition: form-data; name=\"error_action_redirect\""
                                                    || l == "Content-Disposition: attachment; name=\"error_action_redirect\"" =>
                ctx.copy(state = ReadingState.WaitingForBlankLine)
              case ReadingState.WaitingForBlankLine if l == "" =>
                ctx.copy(state = ReadingState.ReadingRedirect)
              case ReadingState.ReadingRedirect if l == s"--$boundary" =>
                ctx.copy(state = ReadingState.Ignore)
              case ReadingState.ReadingRedirect =>
                ctx.copy(redirectUrl = ctx.redirectUrl match {
                  case None     => Some(l)
                  case Some(l1) => Some(l1 + "\n" + l)
                })
              // if we hit the file, then we're not going to find the redirect url
              case _ if (  l.startsWith("Content-Disposition: form-data; name=\"file\";")
                        || l.startsWith("Content-Disposition: attachment; name=\"file\";")
                        ) && ctx.redirectUrl.isEmpty =>
                throw new NoRedirectUrlException
              case _ => ctx
            }
          }
      }
      .recover {
        case _: NoRedirectUrlException => Context(ReadingState.Ignore, None)
      }
      .toMat(Sink.head)(Keep.right)

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
      println("destination..")
      Future(Ok(s"request length was ${request.body.size}"))
    }

  // alternative to parser.raw which doesn't write to memory/disk, but allows streaming straight to a sink
  private def rawParser: BodyParser[Source[ByteString, _]] =
    BodyParser("rawParser") { _ =>
      Accumulator
        .source[ByteString]
	      .map(Right.apply)
  }

   //curl -i -X POST  http://localhost:9000/v1/uploads/magna-test  -H 'content-type: multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW'  -F acl=public-read-write  -F Content-Type=application/text  -F key=colinssource.txt  -F error_action_redirect=https://www.amazon.co.uk  -F file=@/home/colin/Downloads/config-1.3.0.jar

}
