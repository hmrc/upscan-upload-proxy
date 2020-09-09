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

import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import play.api.mvc.{BodyParser, PlayBodyParsers}

import scala.concurrent.ExecutionContext

/*
 * BodyParser[A] has type RequestHeader => Accumulator[ByteString, Either[Result, A]].
 * The framework will handle returning any Left[Result], which frees the controller Action to focus on the valid body
 * scenario (Right[A]).  This BodyParser is therefore unusual in that A is itself an Either.
 *
 * This is presumably to allow us to send a redirect on error, rather than having the framework respond with an error
 * status directly.  However, it is not entirely clear what the purpose of the rawBuffer check is here.  It also
 * neglects to translate any Left[Result] returned from parser.raw - and so not all of this parser's potential errors
 * can be redirected.
 */
object RawParser {
  def parser(parser: PlayBodyParsers)(
    implicit ec: ExecutionContext): BodyParser[Either[String, Source[ByteString, _]]] =
    BodyParser { requestHeader =>
      parser
        .raw(requestHeader)
        .map(_.map { rawBuffer =>
          if (rawBuffer.asFile.exists() && rawBuffer.asFile.canRead)
            Right(FileIO.fromPath(rawBuffer.asFile.toPath))
          else
            Left("<Error><Message>Multipart tmp file was missing</Message></Error>")
        })
    }
}
