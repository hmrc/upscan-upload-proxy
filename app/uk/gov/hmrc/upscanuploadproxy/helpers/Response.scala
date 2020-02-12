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

package uk.gov.hmrc.upscanuploadproxy.helpers
import play.api.libs.json.Json
import play.api.mvc.{Result, Results}
import uk.gov.hmrc.upscanuploadproxy.model.ErrorResponse

import scala.util.Try
import scala.xml.Elem

object Response {

  def badRequest(message: String): Result =
    Results.BadRequest(Json.toJson(ErrorResponse(message))(ErrorResponse.writes))

  def notFound(message: String): Result =
    Results.NotFound(Json.toJson(ErrorResponse(message))(ErrorResponse.writes))

  def internalServerError(message: String): Result =
    Results.InternalServerError(Json.toJson(ErrorResponse(message))(ErrorResponse.writes))

  private def getErrorParameter(elemType: String, xml: Elem): Option[String] =
    (xml \ elemType).headOption.map(node => s"error$elemType=${node.text}")

  private def errorParamsList(body: String): List[String] =
    Try(scala.xml.XML.loadString(body)).toOption.toList.flatMap { xml =>
      val requestId = getErrorParameter("RequestId", xml)
      val resource  = getErrorParameter("Resource", xml)
      val message   = getErrorParameter("Message", xml)
      val code      = getErrorParameter("Code", xml)

      List(code, message, resource, requestId).flatten
    }

  def redirect(url: String, body: String): Result = {
    val errors = errorParamsList(body)
    val queryParams = if (errors.nonEmpty) {
      errors.mkString("?", "&", "")
    } else {
      ""
    }

    Results.Redirect(s"$url$queryParams", 303)
  }

}
