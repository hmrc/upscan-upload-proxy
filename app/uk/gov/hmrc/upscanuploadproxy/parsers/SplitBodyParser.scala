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
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

case class SplitBodyParser[T1, T2](
    parser1: BodyParser[T1]
  , parser2: BodyParser[T2]
  )(implicit ec: ExecutionContext)
    extends BodyParser[(T1, T2)] {

  import SplitBodyParser._

  override def apply(requestHeader: RequestHeader): Accumulator[ByteString, Either[Result, (T1, T2)]] =
    Accumulator(split(
        parser1(requestHeader).toSink
      , parser2(requestHeader).toSink
      ))
}

object SplitBodyParser {

  private def combine[L, T1, T2](
      f1: Future[Either[L, T1]]
    , f2: Future[Either[L, T2]]
    )(implicit ec: ExecutionContext): Future[Either[L, (T1, T2)]] =
      (for {
         o1 <- EitherT(f1)
         o2 <- EitherT(f2)
       } yield (o1, o2)
      ).value

  private def split[T1, T2](
        sink1: Sink[ByteString, Future[Either[Result, T1]]]
      , sink2: Sink[ByteString, Future[Either[Result, T2]]]
      )(implicit ec: ExecutionContext): Sink[ByteString, Future[Either[Result, (T1, T2)]]] =
    Sink.fromGraph(GraphDSL.create(sink1, sink2)(combine) {
      implicit builder => (sink1, sink2) =>
        val broadcast = builder.add(Broadcast[ByteString](outputPorts = 2))
        broadcast.out(0) ~> sink1
        broadcast.out(1) ~> sink2
        SinkShape(broadcast.in)
    })
}
