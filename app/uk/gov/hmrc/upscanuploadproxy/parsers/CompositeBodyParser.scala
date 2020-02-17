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

package uk.gov.hmrc.upscanuploadproxy.parsers

import akka.stream.SinkShape
import akka.stream.scaladsl.GraphDSL.Implicits._
import akka.stream.scaladsl.{Broadcast, GraphDSL, Sink}
import akka.util.ByteString
import play.api.libs.streams.Accumulator
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

case class CompositeBodyParser[T1, T2](
  parser1: BodyParser[T1],
  parser2: BodyParser[T2]
)(implicit ec: ExecutionContext)
    extends BodyParser[(T1, T2)] {

  import CompositeBodyParser._
  override def apply(requestHeader: RequestHeader): Accumulator[ByteString, Either[Result, (T1, T2)]] =
    Accumulator(combineSinks(parser1(requestHeader).toSink, parser2(requestHeader).toSink))
}

object CompositeBodyParser {

  private def combineFE[L, T1, T2](f1: Future[Either[L, T1]], f2: Future[Either[L, T2]])(
    implicit ec: ExecutionContext): Future[Either[L, (T1, T2)]] =
    for {
      e1 <- f1
      e2 <- f2
    } yield
      for {
        v1 <- e1.right
        v2 <- e2.right
      } yield (v1, v2)

  private def combineSinks[T1, T2](
    sink1: Sink[ByteString, Future[Either[Result, T1]]],
    sink2: Sink[ByteString, Future[Either[Result, T2]]]
  )(implicit ec: ExecutionContext): Sink[ByteString, Future[Either[Result, (T1, T2)]]] =
    Sink.fromGraph(GraphDSL.create(sink1, sink2)(combineFE) { implicit builder => (s1, s2) =>
      val broadcast = builder.add(Broadcast[ByteString](outputPorts = 2))
      broadcast.out(0) ~> s1
      broadcast.out(1) ~> s2
      SinkShape(broadcast.in)
    })
}
