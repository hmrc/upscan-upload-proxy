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

package uk.gov.hmrc.upscanuploadproxy.parsers

import akka.stream.scaladsl.GraphDSL.Implicits._
import akka.stream.scaladsl.{Broadcast, GraphDSL, Sink, Source}
import akka.stream.{IOResult, SinkShape}
import akka.util.ByteString
import cats.data.EitherT
import cats.implicits._
import play.api.libs.streams.Accumulator
import play.api.mvc.{BodyParser, PlayBodyParsers}
import uk.gov.hmrc.upscanuploadproxy.parsers.EitherTUtils.combineAs
import uk.gov.hmrc.upscanuploadproxy.parsers.RedirectUrlParser.redirectUrlParser
import uk.gov.hmrc.upscanuploadproxy.parsers.TmpFileParser.tmpFileParser

import scala.concurrent.{ExecutionContext, Future}

// TODO: Make the URL a URI type
case class UpscanRequest(redirectUrl: String, file: Source[ByteString, Future[IOResult]])

object UpscanRequestParser {
  def serviceFileUploadParser(
    implicit ec: ExecutionContext,
    parser: PlayBodyParsers): BodyParser[UpscanRequest] =
    BodyParser { requestHeader =>
      Accumulator {
        Sink.fromGraph {
          GraphDSL.create(
            redirectUrlParser(ec, parser)(requestHeader).toSink,
            tmpFileParser(ec, parser)(requestHeader).toSink
          )(combineAs(UpscanRequest)) { implicit builder =>(rawSink, multipartSink) =>
            val broadcast = builder.add(Broadcast[ByteString](outputPorts = 2))
            broadcast.out(0) ~> rawSink
            broadcast.out(1) ~> multipartSink
            SinkShape(broadcast.in)
          }
        }
      }
    }
}

object EitherTUtils {
  def combineAs[L, T1, T2, R](f: (T1, T2) => R)(f1: Future[Either[L, T1]], f2: Future[Either[L, T2]])(
    implicit ec: ExecutionContext): Future[Either[L, R]] = {
    val result = for {
      o1 <- EitherT(f1)
      o2 <- EitherT(f2)
    } yield f(o1, o2)
    result.value
  }
}
