val scala3Version    = "3.4.0"
val zioVersion       = "2.0.20"
val zioHttpVersion   = "3.0.0-RC4"
val zioJsonVersion   = "0.6.2"
val zioConfigVersion = "4.0.1"

lazy val root = project
  .in(file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name         := "api-gateway",
    version      := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,

    // Fat JAR
    assembly / assemblyJarName := "api-gateway.jar",
    assembly / mainClass       := Some("com.wayrecall.gateway.Main"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "MANIFEST.MF")    => MergeStrategy.discard
      case PathList("META-INF", "services", _*)    => MergeStrategy.concat
      case PathList("META-INF", _*)                => MergeStrategy.discard
      case "reference.conf"                        => MergeStrategy.concat
      case "application.conf"                      => MergeStrategy.concat
      case x if x.endsWith(".proto")               => MergeStrategy.first
      case _                                       => MergeStrategy.first
    },

    libraryDependencies ++= Seq(
      // ZIO Core
      "dev.zio" %% "zio"                    % zioVersion,
      "dev.zio" %% "zio-streams"            % zioVersion,

      // ZIO HTTP — основа gateway
      "dev.zio" %% "zio-http"               % zioHttpVersion,

      // JSON
      "dev.zio" %% "zio-json"               % zioJsonVersion,

      // Config
      "dev.zio" %% "zio-config"             % zioConfigVersion,
      "dev.zio" %% "zio-config-typesafe"    % zioConfigVersion,
      "dev.zio" %% "zio-config-magnolia"    % zioConfigVersion,
      "com.typesafe" % "config"             % "1.4.3",

      // JWT — верификация токенов
      "com.github.jwt-scala" %% "jwt-zio-json" % "10.0.0",

      // Redis (Lettuce) — сессии, позже rate limiting
      "io.lettuce" % "lettuce-core"         % "6.3.2.RELEASE",

      // Logging
      "dev.zio" %% "zio-logging"            % "2.1.16",
      "dev.zio" %% "zio-logging-slf4j"      % "2.1.16",
      "ch.qos.logback" % "logback-classic"  % "1.4.14",

      // Testing
      "dev.zio" %% "zio-test"               % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt"           % zioVersion % Test,
      "dev.zio" %% "zio-test-magnolia"      % zioVersion % Test
    ),

    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
