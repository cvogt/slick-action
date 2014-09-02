import sbt._
import Keys._

object MyBuild extends Build{
  val repoKind = SettingKey[String]("repo-kind", "Maven repository kind (\"snapshots\" or \"releases\")")

  lazy val aRootProject = Project(id = "slick-action", base = file("."),
    settings = Project.defaultSettings ++ Seq(
      name := "Slick Action",
      description := "Automatic connection management preview",
      libraryDependencies ++= Seq(
        "com.typesafe.slick" %% "slick" % "2.1.0"
        ,"com.typesafe.slick" %% "slick-codegen" % "2.1.0"
        ,"org.scalatest" %% "scalatest" % "2.1.6" % "test"
        ,"org.slf4j" % "slf4j-nop" % "1.6.4"
        ,"com.h2database" % "h2" % "1.3.170",
        "org.scala-lang" % "scala-reflect" % "2.10.4"
      ),
      scalacOptions ++= Seq("-feature", "-deprecation", "-unchecked"),
      scalaVersion := "2.10.4",
      version := "0.1-SNAPSHOT",
      organizationName := "Christopher Vogt",
      organization := "com.typesafe",
      scalacOptions in (Compile, doc) <++= (version,sourceDirectory in Compile,name).map((v,src,n) => Seq(
        "-doc-title", n,
        "-doc-version", v,
        "-doc-footer", "Slick is developed by Typesafe and EPFL Lausanne.",
        "-sourcepath", src.getPath, // needed for scaladoc to strip the location of the linked source path
        "-doc-source-url", "https://github.com/cvogt/slick-action/blob/"+v+"/src/main€{FILE_PATH}.scala",
        "-implicits",
        "-diagrams", // requires graphviz
        "-groups"
      )),
      repoKind <<= (version)(v => if(v.trim.endsWith("SNAPSHOT")) "snapshots" else "releases"),
      //publishTo <<= (repoKind)(r => Some(Resolver.file("test", file("c:/temp/repo/"+r)))),
      publishTo <<= (repoKind){
        case "snapshots" => Some("snapshots" at "https://oss.sonatype.org/content/repositories/snapshots")
        case "releases" =>  Some("releases"  at "https://oss.sonatype.org/service/local/staging/deploy/maven2")
      },
      publishMavenStyle := true,
      publishArtifact in Test := false,
      pomIncludeRepository := { _ => false },
      makePomConfiguration ~= { _.copy(configurations = Some(Seq(Compile, Runtime, Optional))) },
      licenses += ("Two-clause BSD-style license", url("http://github.com/slick/slick/blob/master/LICENSE.txt"))
    )
  )
}
