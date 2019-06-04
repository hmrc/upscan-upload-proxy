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
         redirectUrlHolder =  new RedirectUrlHolder
         raw               =  request.body.alsoTo(redirectUrlExtractorSink(boundary, redirectUrlHolder))
         response          <- { println(s"forwarding request")
                                proxyService
                                  .post("http://localhost:9000/destination", raw, getRequiredHeaders(headers))
                                  .map { a =>
                                    println("success")
                                    println(s"redirectUrl=${redirectUrlHolder.redirectUrl}")
                                    a
                                  }
                                  .leftMap { b =>
                                    println("failure")
                                    redirectUrlHolder.redirectUrl match {
                                      case Some(redirectUrl) => Response.redirect(redirectUrl, b)
                                      case None              => Response.badRequest("Could not find error_action_redirect field in request")
                                    }
                                  }
                               }
         _                 =  println("forwarded")
       } yield response
      ).merge
    }

    class RedirectUrlHolder(var redirectUrl: Option[String] = None)

    trait ReadingState
    case object ToRead extends ReadingState
    case object Reading extends ReadingState
    case object Read extends ReadingState

    def redirectUrlExtractorSink(boundary: String, redirectUrlHolder: RedirectUrlHolder): Sink[ByteString, NotUsed] = {
      def sink(boundary: String, redirectUrlHolder: RedirectUrlHolder): Sink[String, Future[ReadingState]] = {
        Sink.fold[ReadingState, String](ToRead){ (state, t) =>
          state match {
            case ToRead if t == "Content-Disposition: form-data; name=\"error_action_redirect\"" => println("->Reading"); Reading
            case ToRead if t == "Content-Disposition: attachment; name=\"error_action_redirect\"" => println("->Reading"); Reading
            case Reading if t == s"--$boundary" => println("->Read"); Read
            case Reading =>
              redirectUrlHolder.redirectUrl = redirectUrlHolder.redirectUrl match {
                case None     => Some(t)
                case Some(t1) => Some(t1 + t)
              }
              println("->Reading2");
              Reading
            case _ => print("."); state
          }
        }
      }

      Flow[ByteString]
        .via(Framing.delimiter(ByteString(LineSeparator.Windows), maximumFrameLength = 1024))
        .map(_.utf8String)
        .to(sink(boundary, redirectUrlHolder))
    }

  def destination =
    Action.async(rawParser) { request =>
      println("destination..")
      Future(Ok.chunked(request.body))
    }

  // alternative to parser.raw which doesn't write to memory/disk, but allows streaming straight to a sink
  private def rawParser: BodyParser[Source[ByteString, _]] =
    BodyParser("rawParser") { _ =>
      Accumulator
        .source[ByteString]
	      .map(Right.apply)
  }
}
