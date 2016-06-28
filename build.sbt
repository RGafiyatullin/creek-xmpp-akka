
name := "creek-xmpp-akka"

version := "0.0"

scalaVersion in ThisBuild := "2.11.8"
val akkaVersion = "2.4.4"

libraryDependencies ++= Seq(
  "org.scalatest"       %% "scalatest"        % "2.2.6",
  "com.typesafe.akka"   %% "akka-actor"       % akkaVersion,
  "com.typesafe.akka"   %% "akka-slf4j"       % akkaVersion,
  "ch.qos.logback"      %  "logback-classic"  % "1.1.3"
)

val creekXmppVersion = "d88c489c7986d817f32c23188901b0b373716882"
val creekXmppUrlBase = "https://github.com/RGafiyatullin/creek-xmpp.git"
val creekXmppUrl = url("%s#%s".format(creekXmppUrlBase, creekXmppVersion))
//val creekXmppUrl = file("../creek-xmpp")
lazy val creekXmppSubProject = RootProject(creekXmppUrl.toURI)

lazy val akkaXmpp = Project("creek-xmpp-akka", file(".")).dependsOn(creekXmppSubProject)


