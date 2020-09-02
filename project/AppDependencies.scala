import play.sbt.PlayImport.ws
import sbt._

object AppDependencies {

  val compile = Seq(
    ws,
    "uk.gov.hmrc"               %% "bootstrap-backend-play-27"  % "2.24.0",
    "com.typesafe.play"         %% "play-json"                  % "2.7.4",
    "org.apache.httpcomponents" %  "httpclient"                 % "4.5.11"
  )

  val test = Seq(
    "org.scalatest"           %% "scalatest"                  % "3.1.0"          % "test, it",
    "com.vladsch.flexmark"    %  "flexmark-all"               % "0.35.10"        % "test, it", // replaces pegdown for newer scalatest "test, it",
    "uk.gov.hmrc"             %% "service-integration-test"   % "0.12.0-play-27" % "test, it",
    "org.scalatestplus.play"  %% "scalatestplus-play"         % "4.0.3"          % "test, it" exclude ("org.eclipse.jetty", "*"),
    "org.mockito"             %% "mockito-scala"              % "1.5.11"         % "test, it",
    "org.mockito"             %% "mockito-scala-scalatest"    % "1.5.11"         % "test, it",
    "com.github.tomakehurst"  %  "wiremock-standalone"        % "2.17.0"         % "test, it"
  )

}
