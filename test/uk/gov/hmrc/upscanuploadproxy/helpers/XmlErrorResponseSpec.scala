/*
 * Copyright 2021 HM Revenue & Customs
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

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.libs.json.Json
import uk.gov.hmrc.upscanuploadproxy.helpers.XmlErrorResponse.{toFields, toJson, toXmlErrorBody}

class XmlErrorResponseSpec extends AnyWordSpecLike with should.Matchers {

  "An XML error message" should {

    "when well-formed and having all fields populated" should {
      val xmlMessage =
        """|<?xml version="1.0" encoding="UTF-8"?>
           |<Error>
           |  <Code>NoSuchKey</Code>
           |  <Message>The resource you requested does not exist</Message>
           |  <Resource>/mybucket/myfoto.jpg</Resource>
           |  <RequestId>4442587FB7D0A2F9</RequestId>
           |</Error>""".stripMargin

      "be parseable into fields" in {
        toFields(key = "a-key", xmlMessage) should contain theSameElementsAs Seq(
          "key"            -> "a-key",
          "errorCode"      -> "NoSuchKey",
          "errorMessage"   -> "The resource you requested does not exist",
          "errorResource"  -> "/mybucket/myfoto.jpg",
          "errorRequestId" -> "4442587FB7D0A2F9"
        )
      }

      "be translatable into JSON" in {
        toJson(key = "a-key", xmlMessage) shouldBe Json.parse(
          """|{
             | "key": "a-key",
             | "errorCode": "NoSuchKey",
             | "errorMessage": "The resource you requested does not exist",
             | "errorResource": "/mybucket/myfoto.jpg",
             | "errorRequestId": "4442587FB7D0A2F9"
             |}""".stripMargin)
      }
    }

    "when well-formed and having only some fields populated" should {
      val xmlMessage =
        """|<?xml version="1.0" encoding="UTF-8"?>
           |<Error>
           |  <Code>NoSuchKey</Code>
           |  <Message>The resource you requested does not exist</Message>
           |</Error>""".stripMargin

      "be parseable into fields" in {
        toFields(key = "a-key", xmlMessage) should contain theSameElementsAs Seq(
          "key"          -> "a-key",
          "errorCode"    -> "NoSuchKey",
          "errorMessage" -> "The resource you requested does not exist"
        )
      }

      "be translatable into JSON" in {
        toJson(key = "a-key", xmlMessage) shouldBe Json.parse(
          """|{
             | "key": "a-key",
             | "errorCode": "NoSuchKey",
             | "errorMessage": "The resource you requested does not exist"
             |}""".stripMargin)
      }
    }

    "when well-formed and having some unrecognised fields" should {
      val xmlMessage =
        """|<?xml version="1.0" encoding="UTF-8"?>
           |<Error>
           |  <Code>NoSuchKey</Code>
           |  <Message>The resource you requested does not exist</Message>
           |  <Unexpected>This field is not in the schema</Unexpected>
           |</Error>""".stripMargin

      "be parseable into fields (silently ignoring unrecognised fields)" in {
        toFields(key = "a-key", xmlMessage) should contain theSameElementsAs Seq(
          "key"          -> "a-key",
          "errorCode"    -> "NoSuchKey",
          "errorMessage" -> "The resource you requested does not exist"
        )
      }

      "be translatable into JSON (silently ignoring unrecognised fields)" in {
        toJson(key = "a-key", xmlMessage) shouldBe Json.parse(
          """|{
             | "key": "a-key",
             | "errorCode": "NoSuchKey",
             | "errorMessage": "The resource you requested does not exist"
             |}""".stripMargin)
      }
    }

    "when not well-formed" should {
      val xmlMessage = "this is not XML"

      "be silently ignored when parsing fields" in {
        toFields(key = "a-key", xmlMessage) should contain theSameElementsAs Seq(
          "key" -> "a-key"
        )
      }

      "be silently ignored when translating into JSON" in {
        toJson(key = "a-key", xmlMessage) shouldBe Json.parse(
          """{"key": "a-key"}"""
        )
      }
    }

    "be creatable from a plain message" in {
      toFields(key = "a-key", toXmlErrorBody("a plain message")) should contain theSameElementsAs Seq(
        "key"          -> "a-key",
        "errorMessage" -> "a plain message"
      )
    }
  }
}
