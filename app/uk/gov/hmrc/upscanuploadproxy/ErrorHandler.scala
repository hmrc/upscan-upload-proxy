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

package uk.gov.hmrc.upscanuploadproxy

import org.apache.pekko.http.scaladsl.model.EntityStreamException
import play.api._
import play.api.http.DefaultHttpErrorHandler
import play.api.mvc._
import play.api.routing.Router
import uk.gov.hmrc.upscanuploadproxy.util.Response

import javax.inject._
import scala.concurrent._

@Singleton
class ErrorHandler @Inject()(
  env         : Environment,
  config      : Configuration,
  sourceMapper: OptionalSourceMapper,
  router      : Provider[Router]
) extends DefaultHttpErrorHandler(env, config, sourceMapper, router)
     with Logging:

  override def onServerError(request: RequestHeader, exception: Throwable): Future[Result] =
    exception match
      case e: EntityStreamException if e.getMessage.contains("Entity stream truncation") =>
        logger.warn("Caught EntityStreamException caused by an aborted upload, returning 500 Internal Server Error")
      case _ =>
        logger.error(s"Internal Server Error, for (${request.method}) [${request.uri}]: ${exception.getMessage}", exception)

    Future.successful(Response.internalServerError("Something went wrong, please try later."))

  override protected def onNotFound(request: RequestHeader, message: String): Future[Result] =
    Future.successful(Response.notFound(s"Path '${request.path}' not found."))

  override protected def onBadRequest(request: RequestHeader, message: String): Future[Result] =
    logger.info(s"Rejected BadRequest - [$message] Headers: [${request.headers.toSimpleMap}]")
    Future.successful(Response.badRequest(s"Bad request: $message"))
