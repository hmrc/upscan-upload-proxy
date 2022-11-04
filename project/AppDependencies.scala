import play.sbt.PlayImport.ws
import sbt._

object AppDependencies {

  val bootstrapVersion = "7.10.0"

  val compile = Seq(
    ws,
    "uk.gov.hmrc"               %% "bootstrap-backend-play-28"  % bootstrapVersion,
    "org.apache.httpcomponents" %  "httpclient"                 % "4.5.13"
  )

  val test = Seq(
    "uk.gov.hmrc"             %% "bootstrap-test-play-28"     % bootstrapVersion % "test, it",
    "uk.gov.hmrc"             %% "service-integration-test"   % "1.3.0-play-28"  % "test, it",
    "org.mockito"             %% "mockito-scala-scalatest"    % "1.16.46"        % "test, it",
    "com.github.tomakehurst"  %  "wiremock-standalone"        % "2.27.2"         % "test, it"
  )

}
