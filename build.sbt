name := "aorta"

version := "0.1-SNAPSHOT"

scalaVersion := "2.11.0-M4"

// src/main/scala is too verbose
scalaSource in Compile := baseDirectory.value

// Whine about everything?
//scalacOptions ++= Seq("-unchecked", "-deprecation", "-Xlint")

// TODO aliases that take args

libraryDependencies += "org.scala-lang" % "scala-swing" % "2.11.0-M4"

libraryDependencies += "org.scala-lang" % "scala-compiler" % scalaVersion.value

libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "1.0-RC3"

libraryDependencies += "org.jfree" % "jfreechart" % "1.0.15"

libraryDependencies += "jline" % "jline" % "2.11"

// Be able to collect all dependencies
packSettings
