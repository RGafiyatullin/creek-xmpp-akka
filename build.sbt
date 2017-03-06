
name := "creek-xmpp-akka"

version := "0.2.17"

scalaVersion in ThisBuild := "2.11.8"
val akkaVersion = "2.4.4"

organization := "com.github.rgafiyatullin"

publishTo := {
  val nexus = "http://nexus.in-docker.localhost:8081/"
  Some("releases"  at nexus + "repository/my-releases")
}
credentials += Credentials(Path.userHome / ".ivy2" / ".credentials.local")

libraryDependencies ++= Seq(
  "org.scalatest"       %% "scalatest"        % "2.2.6",
  "com.typesafe.akka"   %% "akka-actor"       % akkaVersion,
  "com.typesafe.akka"   %% "akka-slf4j"       % akkaVersion,
  "com.typesafe.akka"   %% "akka-testkit"     % akkaVersion,
  "ch.qos.logback"      %  "logback-classic"  % "1.1.3",
  "com.github.rgafiyatullin" %% "creek-xml-binary" % "0.1.0",
  "com.github.rgafiyatullin" %% "creek-xmpp" % "0.3.4",
  "com.github.rgafiyatullin" %% "owl-akka-goodies" % "0.1.6"
)

lazy val akkaXmpp = Project("creek-xmpp-akka", file("."))


