resolvers ++= Seq(
  Classpaths.typesafeSnapshots,
  "sbt-idea-repo" at "http://mpeltonen.github.com/maven/"
)

definedSbtPlugins:= Set("org.sbtidea.SbtIdeaPlugin")

resolvers ++= Seq(
  Classpaths.typesafeSnapshots,
  "sbt-idea-repo" at "http://mpeltonen.github.com/maven/"
)

//addSbtPlugin("com.jsuereth" % "xsbt-gpg-plugin" % "0.6")

libraryDependencies <+= sbtVersion("org.scala-sbt" % "scripted-plugin" % _)
