addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")
addSbtPlugin("com.eed3si9n"  % "sbt-assembly" % "2.2.0")
// native-image: requires GraalVM JDK 21 (`sdk install java 21.0.x-graal` or
// download from https://www.graalvm.org/downloads/). Uncomment to enable
// `sbt lsp/nativeImage` and `sbt cli/nativeImage` (see `make native`).
// addSbtPlugin("com.github.sbt" % "sbt-native-image" % "0.3.4")
