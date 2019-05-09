import sbt._

object Dependencies {
  lazy val dsLink = "org.iot-dsa" % "dslink" % "0.20.1"
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.5"
  lazy val akkaStream = "com.typesafe.akka" %% "akka-stream" % "2.5.22"
}
