import play.sbt.PlayImport.ws
import sbt._

object AppDependencies {

  val compile = Seq(
    ws,
    "uk.gov.hmrc"       %% "bootstrap-play-26" % "0.39.0",
    "com.typesafe.play" %% "play-json"         % "2.7.1"
  )

  val test = Seq(
    "org.scalatest"          %% "scalatest"                % "3.0.4" % "test",
    "org.pegdown"            % "pegdown"                   % "1.6.0" % "test, it",
    "uk.gov.hmrc"            %% "service-integration-test" % "0.2.0" % "test, it",
    "org.scalatestplus.play" %% "scalatestplus-play"       % "2.0.0" % "test, it"
  )

}
