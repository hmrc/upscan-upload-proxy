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

import org.apache.http.client.utils.URIBuilder
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.Results.{BadRequest, InternalServerError, NotFound}
import play.api.mvc.{Result, Results}
import uk.gov.hmrc.upscanuploadproxy.model.ErrorResponse

object Response:

  def badRequest(message: String): Result =
    BadRequest(Json.toJson(ErrorResponse(message))(ErrorResponse.writes))

  def notFound(message: String): Result =
    NotFound(Json.toJson(ErrorResponse(message))(ErrorResponse.writes))

  def internalServerError(message: String): Result =
    InternalServerError(Json.toJson(ErrorResponse(message))(ErrorResponse.writes))

  def redirect(url: String, queryParams: Seq[(String, String)]): Result =
    val urlBuilder = queryParams.foldLeft(URIBuilder(url)): (urlBuilder, param) =>
      urlBuilder.addParameter(param._1, param._2)

    Results.SeeOther(urlBuilder.build().toASCIIString)

  def json(statusCode: Int, body: JsValue): Result =
    Results.Status(statusCode)(body)
