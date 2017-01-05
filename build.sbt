
name := "creek-xmpp-akka"

version := "0.2.6-dev-2"

scalaVersion in ThisBuild := "2.11.8"
val akkaVersion = "2.4.4"

organization := "com.github.rgafiyatullin"

publishTo := {
  val nexus = "http://nexus.in-docker.localhost:8081/"
  Some("releases"  at nexus + "content/repositories/releases")
}
credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")

libraryDependencies ++= Seq(
  "org.scalatest"       %% "scalatest"        % "2.2.6",
  "com.typesafe.akka"   %% "akka-actor"       % akkaVersion,
  "com.typesafe.akka"   %% "akka-slf4j"       % akkaVersion,
  "com.typesafe.akka"   %% "akka-testkit"     % akkaVersion,
  "ch.qos.logback"      %  "logback-classic"  % "1.1.3",
  "com.github.rgafiyatullin" %% "creek-xml-binary" % "0.0.1",
  "com.github.rgafiyatullin" %% "creek-xmpp" % "0.2.2",
  "com.github.rgafiyatullin" %% "owl-akka-goodies" % "0.1.4"
)

lazy val akkaXmpp = Project("creek-xmpp-akka", file("."))


