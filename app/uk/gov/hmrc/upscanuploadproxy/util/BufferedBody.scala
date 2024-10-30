/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.upscanuploadproxy.util

import org.apache.pekko.stream.scaladsl.{FileIO, Source}
import org.apache.pekko.util.ByteString
import play.api.Logger
import play.api.libs.Files.TemporaryFile
import play.api.mvc._

import java.nio.file.Files
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object BufferedBody {
  private val AdoptedFileSuffix = ".out"

  private val logger = Logger(this.getClass)

  def withTemporaryFile[A](request: Request[TemporaryFile], fileReference: Option[String])
                          (block: Try[Source[ByteString, _]] => Future[Result])
                          (implicit ec: ExecutionContext): Future[Result] = {
    val forKey = fileReference.map(key => s" for Key [$key]").getOrElse("")
    val inPath = request.body.path
    val outPath = inPath.resolveSibling(inPath.getFileName.toString + AdoptedFileSuffix)

    val moveFileResult = Try(request.body.atomicMoveWithFallback(outPath))
    Logging.withFileReferenceContext(fileReference.getOrElse("")) {
      moveFileResult.foreach(newPath => logger.debug(s"Moved TemporaryFile$forKey from [$inPath] to [$newPath]"))
    }

    val futResult = block(moveFileResult.map(FileIO.fromPath(_)))
    futResult.onComplete { _ =>
      Future {
        Logging.withFileReferenceContext(fileReference.getOrElse("")) {
          moveFileResult.foreach { path =>
            Try(Files.deleteIfExists(path)).fold(
              err => logger.warn(s"Failed to delete TemporaryFile$forKey at [$path]", err),
              didExist => if (didExist) logger.debug(s"Deleted TemporaryFile$forKey at [$path]")
            )
          }
        }
      }
    }

    futResult
  }
}
