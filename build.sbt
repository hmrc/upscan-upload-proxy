import com.typesafe.sbt.packager.Keys.bashScriptExtraDefines
import uk.gov.hmrc.DefaultBuildSettings.integrationTestSettings
import uk.gov.hmrc.SbtArtifactory
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin.publishingSettings

val appName = "upscan-upload-proxy"

lazy val microservice = Project(appName, file("."))
  .enablePlugins(play.sbt.PlayScala, SbtAutoBuildPlugin, GitVersioning, SbtDistributablesPlugin, SbtArtifactory)
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
