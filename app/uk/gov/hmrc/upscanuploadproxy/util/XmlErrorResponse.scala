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

import play.api.libs.json.{JsValue, Json}

import scala.util.Try
import scala.xml.Elem

object XmlErrorResponse {

  private val KeyName = "key"
  private val MessageField = "Message"

  def toXmlErrorBody(message: String): String =
    s"<Error><$MessageField>$message</$MessageField></Error>"

  def toJson(key: String, xmlErrorBody: String): JsValue =
    Json.toJsObject(toFields(key, xmlErrorBody).toMap)

  def toFields(key: String, xmlErrorBody: String): Seq[(String, String)] =
    (KeyName -> key) +: xmlFields(xmlErrorBody)

  private def xmlFields(xmlErrorBody: String): Seq[(String, String)] =
    Try(scala.xml.XML.loadString(xmlErrorBody)).toOption.toList.flatMap { xml =>
      val requestId = makeOptionalField("RequestId", xml)
      val resource  = makeOptionalField("Resource", xml)
      val message   = makeOptionalField(MessageField, xml)
      val code      = makeOptionalField("Code", xml)

      Seq(code, message, resource, requestId).flatten
    }

  private def makeOptionalField(elemType: String, xml: Elem): Option[(String, String)] =
    (xml \ elemType).headOption.map(node => s"error$elemType" -> node.text)
}
