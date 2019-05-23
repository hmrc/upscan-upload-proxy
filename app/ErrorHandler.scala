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

import javax.inject._
import play.api._
import play.api.http.DefaultHttpErrorHandler
import play.api.mvc._
import play.api.routing.Router
import uk.gov.hmrc.upscanuploadproxy.helper.Response

import scala.concurrent._

@Singleton
class ErrorHandler @Inject()(
  env: Environment,
  config: Configuration,
  sourceMapper: OptionalSourceMapper,
  router: Provider[Router])
    extends DefaultHttpErrorHandler(env, config, sourceMapper, router) {

  private val logger: Logger = Logger(this.getClass)

  override protected def onDevServerError(request: RequestHeader, exception: UsefulException): Future[Result] =
    onProdServerError(request, exception)

  override def onProdServerError(request: RequestHeader, exception: UsefulException): Future[Result] = {
    logger.error("Internal server error", exception)
    Future.successful(Response.internalServerError("Something went wrong, please try later."))
  }

  override protected def onNotFound(request: RequestHeader, message: String): Future[Result] =
    Future.successful(Response.notFound(s"Path '${request.path}' not found."))

  override protected def onBadRequest(request: RequestHeader, message: String): Future[Result] =
    Future.successful(Response.badRequest(s"Bad request"))
}
