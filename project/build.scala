import sbt._
import Keys._

object MyBuild extends Build{
  override lazy val settings = super.settings ++ Seq(
    libraryDependencies ++= Seq(
      "com.typesafe.slick" %% "slick" % "2.1.0"
      ,"com.typesafe.slick" %% "slick-codegen" % "2.1.0"
      ,"org.scalatest" %% "scalatest" % "2.1.6" % "test"
      ,"org.slf4j" % "slf4j-nop" % "1.6.4"
      ,"com.h2database" % "h2" % "1.3.170"
    ),
    scalacOptions ++= Seq("-feature", "-deprecation", "-unchecked"),
    scalaVersion := "2.10.4"
  )
}
