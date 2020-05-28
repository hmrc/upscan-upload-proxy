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


object RawParser {
  def parser(parser: PlayBodyParsers)(implicit ec: ExecutionContext): BodyParser[Either[String, Source[ByteString, _]]] =
    BodyParser { requestHeader =>
        parser
          .raw(requestHeader)
          .map(_.right.map(rawBuffer =>
              if(rawBuffer.asFile.exists() && rawBuffer.asFile.canRead)
                Right(FileIO.fromPath(rawBuffer.asFile.toPath))
              else
                Left(s"Multipart tmp file was missing")
            )
          )
    }
}
