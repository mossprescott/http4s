name := "http4s-netty"

description := "Netty backend for http4s"

libraryDependencies ++= Seq(
  "play" %% "play-iteratees" % "2.1-RC3",
  "org.specs2" %% "specs2" % "1.13" % "test",
  "junit" % "junit" % "4.11" % "test",
  "org.jboss.netty" % "netty" % "3.2.9.Final",
  "com.typesafe" %% "scalalogging-slf4j" % "1.0.1",
  "ch.qos.logback" % "logback-parent" % "1.0.9"
)