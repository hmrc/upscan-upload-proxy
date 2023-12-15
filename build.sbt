import com.typesafe.sbt.packager.Keys.{bashScriptExtraDefines, dockerBaseImage, dockerRepository, dockerUpdateLatest}

val appName = "upscan-upload-proxy"

ThisBuild / scalaVersion := "2.13.12"

lazy val microservice = Project(appName, file("."))
  .enablePlugins(play.sbt.PlayScala, SbtDistributablesPlugin)
  .settings(
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test
  )
  .settings(bashScriptExtraDefines += """addJava "-Xms256M"""", bashScriptExtraDefines += """addJava "-Xmx2000M"""")
  .settings(PlayKeys.devSettings += "play.server.http.idleTimeout" -> "900 seconds")
  .settings(resolvers += Resolver.jcenterRepo)
  .settings(majorVersion := 0)
  .settings(Seq(
    dockerUpdateLatest := true,
    dockerBaseImage := "artefacts.tax.service.gov.uk/hmrc-jre:latest",
    dockerRepository := Some("artefacts.tax.service.gov.uk")))
