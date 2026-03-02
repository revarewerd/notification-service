// =============================================================
// NOTIFICATION SERVICE — build.sbt
// =============================================================

val zioVersion         = "2.0.20"
val zioKafkaVersion    = "2.7.3"
val zioHttpVersion     = "3.0.0-RC4"
val zioConfigVersion   = "4.0.0-RC16"
val zioJsonVersion     = "0.6.2"
val zioRedisVersion    = "1.0.0-RC1"
val doobieVersion      = "1.0.0-RC4"
val interopCatsVersion = "23.1.0.0"

lazy val root = (project in file("."))
  .settings(
    name := "notification-service",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "3.4.0",
    organization := "com.wayrecall",

    libraryDependencies ++= Seq(
      // ZIO Core
      "dev.zio" %% "zio"                 % zioVersion,
      "dev.zio" %% "zio-streams"         % zioVersion,

      // Kafka
      "dev.zio" %% "zio-kafka"           % zioKafkaVersion,

      // HTTP (REST API + HTTP client для webhook/telegram)
      "dev.zio" %% "zio-http"            % zioHttpVersion,

      // Config
      "dev.zio" %% "zio-config"          % zioConfigVersion,
      "dev.zio" %% "zio-config-typesafe" % zioConfigVersion,
      "dev.zio" %% "zio-config-magnolia" % zioConfigVersion,

      // JSON
      "dev.zio" %% "zio-json"            % zioJsonVersion,

      // Doobie (PostgreSQL)
      "org.tpolecat" %% "doobie-core"    % doobieVersion,
      "org.tpolecat" %% "doobie-hikari"  % doobieVersion,
      "org.tpolecat" %% "doobie-postgres" % doobieVersion,
      "dev.zio" %% "zio-interop-cats"    % interopCatsVersion,

      // PostgreSQL driver
      "org.postgresql" % "postgresql"     % "42.7.1",
      "com.zaxxer"     % "HikariCP"      % "5.1.0",

      // Logging
      "ch.qos.logback"     % "logback-classic"          % "1.4.14",
      "com.fasterxml.jackson.core" % "jackson-databind"  % "2.16.1",

      // Email (JavaMail)
      "com.sun.mail" % "javax.mail" % "1.6.2",

      // Test
      "dev.zio" %% "zio-test"            % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt"        % zioVersion % Test,
      "dev.zio" %% "zio-test-magnolia"   % zioVersion % Test
    ),

    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),

    scalacOptions ++= Seq(
      "-encoding", "utf8",
      "-deprecation",
      "-unchecked",
      "-feature",
      "-language:postfixOps"
    ),

    assembly / mainClass := Some("com.wayrecall.tracker.notifications.Main"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case "module-info.class"           => MergeStrategy.discard
      case x                             => MergeStrategy.first
    }
  )
