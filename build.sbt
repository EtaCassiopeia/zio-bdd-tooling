ThisBuild / organization := "io.github.etacassiopeia"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.4"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked")

// Default: depend on the latest released zio-bdd artifacts from Maven Central.
// To develop against an unreleased core change instead (e.g. while #93's prerequisite
// PR https://github.com/EtaCassiopeia/zio-bdd/pull/103 is still pending release),
// run `sbt publishLocal` in a zio-bdd checkout and override this to the local
// SNAPSHOT version it prints — see BUILDING.md. Bump back to a released version once
// that PR ships in a tagged release.
// `sbt publishLocal` (not publishM2) publishes to ~/.ivy2/local, which sbt already
// resolves from by default — no extra resolver needed here. Override with
// `-DzioBdd.version=<local-snapshot>` to build against an unreleased core change.
val zioBddVersion = sys.props.getOrElse("zioBdd.version", "1.0.0")

lazy val lsp = (project in file("lsp"))
  .settings(
    name := "zio-bdd-lsp",
    libraryDependencies ++= Seq(
      "io.github.etacassiopeia" %% "zio-bdd"                   % zioBddVersion,
      "io.github.etacassiopeia" %% "zio-bdd-gherkin"            % zioBddVersion,
      // scalameta intentionally not used: scalameta_3 isn't resolvable in every
      // target environment, and _2.13 requires scalapb, which creates unresolvable
      // _2.13/_3 cross-version conflicts with ZIO's _3 dependencies. Step extraction
      // uses a lightweight regex + paren-counting parser instead (see StepExtractor).
      "org.eclipse.lsp4j" % "org.eclipse.lsp4j"         % "0.21.2",
      "org.eclipse.lsp4j" % "org.eclipse.lsp4j.jsonrpc" % "0.21.2",
      "dev.zio"           %% "zio"                      % "2.1.17",
      "dev.zio"           %% "zio-test"                 % "2.1.17" % Test,
      "dev.zio"           %% "zio-test-sbt"              % "2.1.17" % Test,
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),

    // Fat jar — all deps merged into a single executable jar
    assembly / assemblyJarName := "zio-bdd-lsp.jar",
    assembly / mainClass       := Some("zio.bdd.lsp.Main"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat
      case PathList("META-INF", xs @ _*)             => MergeStrategy.discard
      case PathList("module-info.class")             => MergeStrategy.discard
      case PathList("reference.conf")                => MergeStrategy.concat
      case x                                          => MergeStrategy.first
    },
    // Copy the fat jar to extensions/intellij/src/main/resources/bin/ so Gradle can
    // bundle it into the plugin zip.
    assembly / assemblyOutputPath := {
      baseDirectory.value / ".." / "extensions" / "intellij" / "src" / "main" / "resources" / "bin" / "zio-bdd-lsp.jar"
    },
  )

lazy val root = (project in file("."))
  .aggregate(lsp)
  .settings(
    name           := "zio-bdd-tooling",
    publish / skip := true,
  )
