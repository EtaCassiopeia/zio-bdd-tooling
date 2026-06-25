package zio.bdd.cli

import zio.*
import zio.test.*
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object CliSpec extends ZIOSpecDefault:
  def spec = suite("CLI Scanner")(
    test("collectStepDefs finds Given/When/Then in Scala source") {
      val tmp = Files.createTempDirectory("cli-test")
      val scala = tmp.resolve("Steps.scala")
      Files.writeString(scala,
        """
          |object Steps:
          |  Given("a user exists") { ZIO.unit }
          |  When("they log in") { ZIO.unit }
          |  Then("they see the dashboard") { ZIO.unit }
          |""".stripMargin
      )
      val defs = Scanner.collectStepDefs(tmp)
      assertTrue(
        defs.length == 3,
        defs.map(_.keyword).toSet == Set("Given", "When", "Then"),
        defs.exists(_.displayText == "a user exists")
      )
    },

    test("unmatchedSteps returns steps that have no matching definition") {
      val tmp = Files.createTempDirectory("cli-unmatched")
      val feature = tmp.resolve("test.feature")
      Files.writeString(feature,
        """
          |Feature: Login
          |  Scenario: Happy path
          |    Given a user exists
          |    When they log in
          |    Then they see the dashboard
          |""".stripMargin
      )
      val scala = tmp.resolve("Steps.scala")
      Files.writeString(scala,
        """
          |object Steps:
          |  Given("a user exists") { ZIO.unit }
          |""".stripMargin
      )
      val unmatched = Scanner.unmatchedSteps(tmp)
      assertTrue(
        unmatched.length == 2,
        unmatched.exists(_.text == "they log in"),
        unmatched.exists(_.text == "they see the dashboard")
      )
    },

    test("unmatchedSteps returns empty when all steps are matched") {
      val tmp = Files.createTempDirectory("cli-matched")
      Files.writeString(tmp.resolve("test.feature"),
        """
          |Feature: Login
          |  Scenario: Happy path
          |    Given a user exists
          |""".stripMargin
      )
      Files.writeString(tmp.resolve("Steps.scala"),
        """
          |object Steps:
          |  Given("a user exists") { ZIO.unit }
          |""".stripMargin
      )
      val unmatched = Scanner.unmatchedSteps(tmp)
      assertTrue(unmatched.isEmpty)
    },

    test("toSkeleton produces a valid step skeleton for an unmatched step") {
      val tmp  = Path.of("/tmp")
      val u    = Scanner.UnmatchedStep("Given", "a user named \"Alice\" exists", tmp.resolve("f.feature"), 5)
      val skel = Scanner.toSkeleton(u)
      assertTrue(
        skel.contains("Given("),
        skel.contains("ZIO.unit"),
        skel.contains("\\\"Alice\\\"")
      )
    },
  )
