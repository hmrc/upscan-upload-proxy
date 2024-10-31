import com.typesafe.sbt.packager.Keys.{bashScriptExtraDefines, dockerBaseImage, dockerRepository, dockerUpdateLatest}

ThisBuild / majorVersion  := 0
ThisBuild / scalaVersion  := "3.3.4"
ThisBuild / scalacOptions += "-Wconf:msg=Flag.*repeatedly:s"

lazy val microservice = Project("upscan-upload-proxy", file("."))
  .enablePlugins(PlayScala, SbtDistributablesPlugin)
  .settings(
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test
  )
  .settings( bashScriptExtraDefines += """addJava "-Xms256M""""
           , bashScriptExtraDefines += """addJava "-Xmx2000M""""
           )
  .settings(PlayKeys.devSettings += "play.server.http.idleTimeout" -> "900 seconds")
  .settings(resolvers += Resolver.jcenterRepo)
  .settings(scalacOptions += "-Wconf:src=routes/.*:s")
  .settings(Seq(
    dockerUpdateLatest := true,
    dockerBaseImage    := "artefacts.tax.service.gov.uk/hmrc-jre:latest",
    dockerRepository   := Some("artefacts.tax.service.gov.uk")
  ))
