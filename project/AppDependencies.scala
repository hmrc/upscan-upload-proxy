import play.sbt.PlayImport.ws
import sbt._

object AppDependencies {

  val compile = Seq(
    ws,
    "uk.gov.hmrc"       %% "bootstrap-play-26" % "0.39.0",
    "com.typesafe.play" %% "play-json"         % "2.7.1",
    "org.typelevel"     %% "cats-core"         % "1.6.0"
  )

  val test = Seq(
    "org.scalatest"          %% "scalatest"                % "3.0.5"  % "test, it",
    "org.pegdown"            % "pegdown"                   % "1.6.0"  % "test, it",
    "uk.gov.hmrc"            %% "service-integration-test" % "0.2.0"  % "test, it",
    "org.scalatestplus.play" %% "scalatestplus-play"       % "3.1.1"  % "test, it" exclude ("org.eclipse.jetty", "*"),
    "org.mockito"            % "mockito-core"              % "2.15.0" % "test, it",
    "com.github.tomakehurst" % "wiremock-standalone"       % "2.17.0" % "test, it"
  )

}
