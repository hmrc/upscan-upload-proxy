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
      val url         = uriGenerator.uri(destination)

      val optBoundary = request.headers.get("Content-Type")
                          .flatMap(ct => scala.util.Try(ct.split("boundary=")(1)).toOption)

      val headers     = request.headers.headers

      (for {
         boundary          <- EitherT.fromEither[Future](optBoundary
                                .toRight(Response.badRequest("Missing boundary in headers"))
                              )
         mat1: (Future[Either[String, Result]], Future[Context]) =
            request.body
            .alsoToMat(fileUpload(headers))(Keep.right)
            .toMat(redirectUrlExtractorSink(boundary))(Keep.both)
            .run()

         (fResponse, fCtx) = mat1
         ctx <- EitherT.liftF(fCtx)
         _ = println(s"ctx finished: $ctx")
         response <- EitherT(fResponse)
                       .leftMap { b =>
                                    println(s"failure: $b - redirecting to ${ctx.redirectUrl}")
                                    ctx.redirectUrl match {
                                      case Some(redirectUrl) => Response.redirect(redirectUrl, b)
                                      case None              => Response.badRequest("Could not find error_action_redirect field in request")
                                    }
                                  }
         _                 =  println("response finished")
       } yield response.withHeaders("X-redirectUrl" -> ctx.redirectUrl.toString)
      ).merge
    }

    class RedirectUrlHolder(var redirectUrl: Option[String] = None)

    trait ReadingState
    object ReadingState {
      case object WaitingForRedirect  extends ReadingState
      case object WaitingForBlankLine extends ReadingState
      case object Reading extends ReadingState
      case object Read    extends ReadingState
    }

    case class Context(state: ReadingState, redirectUrl: Option[String])

    def redirectUrlExtractorSink(boundary: String): Sink[ByteString, Future[Context]] =
      Flow[ByteString]
        // FIXME akka.stream.scaladsl.Framing$FramingException: Read 15742 bytes which is more than 1024 without seeing a line terminator
        //.via(Framing.delimiter(ByteString(LineSeparator.Windows), maximumFrameLength = 140000, allowTruncation = true))
        // .recoverWith {
        //   case e: RuntimeException => println(s"stream failure: $e"); Flow[ByteString]
        // }
        .sliding(2) // as long as two chunk size (default 131072?) contain a full line?
        .mapConcat { _.map(_.utf8String).mkString.split(LineSeparator.Windows).toList}
        .fold[Context](Context(ReadingState.WaitingForRedirect, None)){ (ctx, t) =>
          ctx.state match {
            case ReadingState.WaitingForRedirect if t == "Content-Disposition: form-data; name=\"error_action_redirect\""
                                                 || t == "Content-Disposition: attachment; name=\"error_action_redirect\"" =>
              ctx.copy(state = ReadingState.WaitingForBlankLine)
            case ReadingState.WaitingForBlankLine if t == "" =>
              ctx.copy(state = ReadingState.Reading)
            case ReadingState.Reading if t == s"--$boundary" =>
              ctx.copy(state = ReadingState.Read)
            case ReadingState.Reading =>
              ctx.copy(redirectUrl = ctx.redirectUrl match {
                case None     => Some(t)
                case Some(t1) => Some(t1 + "\n" + t)
              })
            case _ => ctx
          }
        }
        .toMat(Sink.head)(Keep.right)

  def fileUpload(headers: Seq[(String, String)]): Sink[ByteString, Future[Either[String, Result]]] =
   Sink
      .asPublisher[ByteString](fanout = false)
      .mapMaterializedValue(Source.fromPublisher)
      .mapMaterializedValue { source =>
        proxyService
          .post("http://localhost:9000/destination", source, getRequiredHeaders(headers)).value
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
