
name := "creek-xmpp-akka"

version := "0.3.2.2"

scalaVersion in ThisBuild := "2.11.8"
val akkaVersion = "2.4.4"

organization := "com.github.rgafiyatullin"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")
scalacOptions ++= Seq("-language:implicitConversions")
scalacOptions ++= Seq("-Ywarn-value-discard", "-Xfatal-warnings")


publishTo := {
  Some("releases"  at "https://artifactory.wgdp.io:443/xmppcs-maven-releases/")
}
credentials += Credentials(Path.userHome / ".ivy2" / ".credentials.wg-domain")

/*
publishTo := {
  val nexus = "http://nexus.in-docker.localhost:8081/"
  Some("releases"  at nexus + "repository/my-releases")
}
credentials += Credentials(Path.userHome / ".ivy2" / ".credentials.local")
*/

libraryDependencies ++= Seq(
  "org.scalatest"       %% "scalatest"        % "3.0.4",
  "com.typesafe.akka"   %% "akka-actor"       % akkaVersion,
  "com.typesafe.akka"   %% "akka-stream"      % akkaVersion,
  "com.typesafe.akka"   %% "akka-slf4j"       % akkaVersion,
  "com.typesafe.akka"   %% "akka-testkit"     % akkaVersion,
  "ch.qos.logback"      %  "logback-classic"  % "1.1.3",
  "com.github.rgafiyatullin" %% "creek-xml-binary" % "0.1.1",
  "com.github.rgafiyatullin" %% "creek-xmpp" % "0.3.7.2",
  "com.github.rgafiyatullin" %% "owl-akka-goodies" % "0.1.8.1"
)

lazy val akkaXmpp = Project("creek-xmpp-akka", file("."))


