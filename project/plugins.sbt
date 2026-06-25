addSbtPlugin("org.scalameta" % "sbt-scalafmt"    % "2.5.2")
addSbtPlugin("com.eed3si9n"  % "sbt-assembly"    % "2.2.0")
// Requires GraalVM JDK 21 on PATH (`sdk install java 21.0.x-graal`).
// Provides `sbt lsp/nativeImage` → bin/zio-bdd-lsp
//          `sbt cli/nativeImage` → bin/zio-bdd
addSbtPlugin("org.scalameta" % "sbt-native-image" % "0.3.4")
