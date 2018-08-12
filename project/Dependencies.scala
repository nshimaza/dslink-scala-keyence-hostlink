import sbt._

object Dependencies {
  lazy val dsLink = "org.iot-dsa" % "dslink" % "0.18.5"
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.3"
//  lazy val akkaActor = "com.typesafe.akka" %% "akka-actor" % "2.5.12"
  lazy val akkaStream = "com.typesafe.akka" %% "akka-stream" % "2.5.14"
}
