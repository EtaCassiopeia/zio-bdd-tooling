package zio.bdd.lsp

import zio.*
import zio.test.*

object WorkspaceIndexSpec extends ZIOSpecDefault:

  def spec = suite("WorkspaceIndex.ownerSuiteForFeature")(
    test("resolves the owning suite and drops it when the suite file is removed") {
      val suiteSrc =
        """@Suite(featureDirs = Array("/wi/features"))
          |object WiSuite extends ZIOSteps[Any, S]
          |""".stripMargin
      for
        index <- ZIO.service[WorkspaceIndex]
        _     <- index.indexScalaFile("/wi/WiSuite.scala", suiteSrc)
        owned <- index.ownerSuiteForFeature("/wi/features/x.feature")
        _     <- index.removeFile("/wi/WiSuite.scala")
        gone  <- index.ownerSuiteForFeature("/wi/features/x.feature")
      yield assertTrue(owned == Some("WiSuite"), gone.isEmpty)
    }
  ).provide(WorkspaceIndex.layer)
