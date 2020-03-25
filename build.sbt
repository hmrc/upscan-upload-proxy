import com.typesafe.sbt.packager.Keys.{bashScriptExtraDefines, dockerBaseImage, dockerRepository, dockerUpdateLatest}
import uk.gov.hmrc.DefaultBuildSettings.integrationTestSettings
import uk.gov.hmrc.SbtArtifactory
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin.publishingSettings
import uk.gov.hmrc.versioning.SbtGitVersioning.autoImport.majorVersion

val appName = "upscan-upload-proxy"

lazy val microservice = Project(appName, file("."))
  .enablePlugins(play.sbt.PlayScala, SbtAutoBuildPlugin, GitVersioning, SbtDistributablesPlugin, SbtArtifactory)
  .settings(scalaVersion := "2.12.8")
  .settings(
    libraryDependencies              ++= AppDependencies.compile ++ AppDependencies.test
  )
  .settings(    
    bashScriptExtraDefines += """addJava "-Xms256M"""",
    bashScriptExtraDefines += """addJava "-Xmx2000M"""")
  .settings(publishingSettings: _*)
  .settings(PlayKeys.devSettings += "play.server.http.idleTimeout" -> "900 seconds")
  .configs(IntegrationTest)
  .settings(integrationTestSettings(): _*)
  .settings(resolvers += Resolver.jcenterRepo)
  .settings(majorVersion := 0)
  .settings(Seq(
    dockerUpdateLatest := true,
    dockerBaseImage := "artefacts.tax.service.gov.uk/hmrc-jre:latest",
    dockerRepository := Some("artefacts.tax.service.gov.uk")))