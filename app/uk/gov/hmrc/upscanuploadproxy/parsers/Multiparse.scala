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

import akka.stream.scaladsl.{Flow, Keep, Sink}
import akka.util.ByteString
import org.apache.xml.serialize.LineSeparator
import scala.concurrent.{ExecutionContext, Future}

object Multiparse {

  private trait ReadingState
  private object ReadingState {
    case object WaitingForName  extends ReadingState
    case object WaitingForBlankLine extends ReadingState
    case object Reading             extends ReadingState
    case object Ignore              extends ReadingState
  }

  private case class Context(state: ReadingState, redirectUrl: Option[String])

  private class NoRedirectUrlException extends RuntimeException

  private val KeyValue = """^([a-zA-Z_0-9]+)="?(.*?)"?$""".r

  private def isContentDisposition(line: String, name: String) = {
    if (line.toLowerCase.startsWith("content-disposition")) {
      val map = line.split(";").map(_.trim).map {
        case KeyValue(key, v) => (key.trim, v.trim)
        case key              => (key.trim, ""    )
       }.toMap
       map.get("name").map(_ == name).getOrElse(false)
    } else false
  }

  private def toLines(v: Seq[ByteString]): Seq[String] =
    v.map(_.utf8String).mkString.split(LineSeparator.Windows).toList

  /** Extracts the value for a multipart field.
    * It assumes all meta data comes before the first file part, so
    * will terminate early if the field is not found before the first file part.
    * @param boundary to delimite the multi parts
    * @param name of multipart field to extract value
    */
  def extractMetaSink(boundary: String, name: String)(implicit ec: ExecutionContext): Sink[ByteString, Future[Option[String]]] =
    Flow[ByteString]
      .sliding(2) // as long as two chunk size (default 131072?) contain a full line?
      .fold[Context](Context(ReadingState.WaitingForName, None)){ (ctx, v) =>
        // optimised to avoid converting to lines once we've found the redirect
        if (ctx.state == ReadingState.Ignore) ctx
        else
          toLines(v).foldLeft(ctx){ (ctx, line) =>
            ctx.state match {
              case ReadingState.WaitingForName if isContentDisposition(line, name) =>
                ctx.copy(state = ReadingState.WaitingForBlankLine)
              case ReadingState.WaitingForBlankLine if line == "" =>
                ctx.copy(state = ReadingState.Reading)
              case ReadingState.Reading if line == s"--$boundary" =>
                ctx.copy(state = ReadingState.Ignore)
              case ReadingState.Reading =>
                ctx.copy(redirectUrl = ctx.redirectUrl match {
                  case None     => Some(line)
                  case Some(l1) => Some(l1 + "\n" + line)
                })
              // if we hit the file, then we're not going to find the redirect url
              case _ if isContentDisposition(line, "file") && ctx.redirectUrl.isEmpty =>
                throw new NoRedirectUrlException
              case _ => ctx
            }
          }
      }
      .recover {
        case _: NoRedirectUrlException => Context(ReadingState.Ignore, None)
      }
      .toMat(Sink.head)(Keep.right).mapMaterializedValue(_.map(_.redirectUrl))

}