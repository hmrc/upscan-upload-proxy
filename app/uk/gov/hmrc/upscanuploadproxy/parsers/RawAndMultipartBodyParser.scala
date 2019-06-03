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
import akka.stream.SinkShape
import akka.stream.scaladsl.GraphDSL.Implicits._
import akka.stream.scaladsl.{Broadcast, GraphDSL, Sink}
import akka.util.ByteString
import cats.data.EitherT
import cats.implicits._
import play.api.libs.streams.Accumulator
import play.api.mvc.MultipartFormData.FilePart
import play.api.mvc._
import play.core.parsers.Multipart
import play.core.parsers.Multipart.FileInfo

import scala.concurrent.{ExecutionContext, Future}

case class RawAndMultipartBodyParser(
  rawParser: BodyParser[RawBuffer],
  multipartParser: Multipart.FilePartHandler[Unit] => BodyParser[MultipartFormData[Unit]]
)(implicit ec: ExecutionContext)
    extends BodyParser[(RawBuffer, MultipartFormData[Unit])] {

  private def fileIgnoreHandler(): Multipart.FilePartHandler[Unit] = {
    case FileInfo(partName, filename, contentType) =>
      Accumulator(Sink.ignore).mapFuture(_ => Future.successful(FilePart(partName, filename, contentType, ())))
  }

  private def combine[L, T1, T2](f1: Future[Either[L, T1]], f2: Future[Either[L, T2]]): Future[Either[L, (T1, T2)]] = {
    val result = for {
      o1 <- EitherT(f1)
      o2 <- EitherT(f2)
    } yield (o1, o2)
    result.value
  }

  type Acc[T] = Accumulator[ByteString, Either[Result, T]]
  override def apply(requestHeader: RequestHeader): Acc[(RawBuffer, MultipartFormData[Unit])] = {
    val rawAccumulator       = rawParser(requestHeader)
    val multipartAccumulator = multipartParser(fileIgnoreHandler())(requestHeader)
    val combinedSink = Sink.fromGraph(GraphDSL.create(rawAccumulator.toSink, multipartAccumulator.toSink)(combine) {
      implicit builder => (rawSink, multipartSink) =>
        val broadcast = builder.add(Broadcast[ByteString](outputPorts = 2))
        broadcast.out(0) ~> rawSink
        broadcast.out(1) ~> multipartSink
        SinkShape(broadcast.in)
    })
    Accumulator(combinedSink)
  }
}
