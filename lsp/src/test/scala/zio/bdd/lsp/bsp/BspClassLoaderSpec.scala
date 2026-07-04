package zio.bdd.lsp.bsp

import zio.test.*
import zio.bdd.lsp.{RuntimeMockSummary, RuntimeStepSummary}

object BspClassLoaderSpec extends ZIOSpecDefault:

  private val envelope =
    """{"steps":[{"keyword":"Given","pattern":"^ready$","displayText":"ready"}],""" +
      """"mocks":[{"name":"userService","sourceKind":"Dsl"},{"name":"paymentGateway","sourceKind":"Json"}]}"""

  def spec = suite("BspClassLoader")(
    test("parses step summaries from the {steps,mocks} envelope, ignoring mock objects") {
      assertTrue(
        BspClassLoader.parseSteps(envelope) ==
          List(RuntimeStepSummary("Given", "^ready$", "ready"))
      )
    },
    test("parses mock summaries from the envelope, ignoring step objects") {
      assertTrue(
        BspClassLoader.parseMocks(envelope) ==
          List(RuntimeMockSummary("userService", "Dsl"), RuntimeMockSummary("paymentGateway", "Json"))
      )
    },
    test("an empty envelope yields no steps and no mocks") {
      val empty = """{"steps":[],"mocks":[]}"""
      assertTrue(BspClassLoader.parseSteps(empty).isEmpty, BspClassLoader.parseMocks(empty).isEmpty)
    },
    test("a mocks-only envelope yields no steps") {
      val json = """{"steps":[],"mocks":[{"name":"svc","sourceKind":"File"}]}"""
      assertTrue(
        BspClassLoader.parseSteps(json).isEmpty,
        BspClassLoader.parseMocks(json) == List(RuntimeMockSummary("svc", "File"))
      )
    }
  )
