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

package uk.gov.hmrc.upscanuploadproxy.helpers

import org.slf4j.MDC

/*
 * Relies on the project being configured to use bootstrap's MDCPropagatingExecutorService
 */
object Logging {
  def withFileReferenceContext[A](fileReference: String)(f: => A): A =
    try {
      MDC.put("file-reference", fileReference)
      f
    } finally {
      MDC.remove("file-reference")
    }
}