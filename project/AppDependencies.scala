import play.sbt.PlayImport.ws
import sbt._

object AppDependencies {

  val bootstrapVersion = "9.5.0"

  val compile = Seq(
    ws,
    "uk.gov.hmrc"               %% "bootstrap-backend-play-30"  % bootstrapVersion,
    "org.apache.httpcomponents" %  "httpclient"                 % "4.5.13"
  )

  val test = Seq(
    "uk.gov.hmrc"               %% "bootstrap-test-play-30"     % bootstrapVersion % Test,
  )
}
