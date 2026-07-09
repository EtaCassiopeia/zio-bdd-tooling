package zio.bdd.lsp.bsp

import zio.test.*

// ── Reflection fixtures (top-level so they have genuine no-arg constructors) ──
//
// The loader never sees zio-bdd's real `ZIOSteps`/`MockSteps`/`StepSummary` types —
// it drives everything through reflection (no-arg construction, an `allDefinitions`
// method, and `keyword`/`pattern`/`displayText` accessors on each summary). A
// `MockSteps`-based suite is structurally identical along that path: the self-type
// mixin `{ self: ZIOSteps[R & MockControl, S] => }` adds no constructor parameters
// and `MockControl` is only needed inside step bodies the loader never runs. So a
// plain class with a no-arg constructor and an `allDefinitions` method faithfully
// stands in for a mocking suite here.

final case class FakeSummary(keyword: String, pattern: String, displayText: String)

// Stands in for zio-bdd's MockSummary(name, sourceKind) — the loader reads it
// purely by reflection (`name`/`sourceKind` accessors), so a plain case class is
// a faithful stand-in for a MockSteps suite's `allMocks` element.
final case class FakeMock(name: String, sourceKind: String)

class GoodSuite:
  def allDefinitions: List[FakeSummary] =
    List(FakeSummary("Given", "^a cart with (\\d+) items$", "a cart with {int} items"))

// A suite exposing both step and mock discovery — the shape of a MockSteps-based
// suite that overrides `mockCatalog`. Order mirrors what `allMocks` returns.
class MockCatalogSuite:
  def allDefinitions: List[FakeSummary] =
    List(FakeSummary("Given", "^ready$", "ready"))
  def allMocks: List[FakeMock] =
    List(FakeMock("paymentGateway", "Json"), FakeMock("userService", "Dsl"))

class ThrowingMocksSuite:
  def allMocks: List[FakeMock] =
    throw new RuntimeException("allMocks boom")

class NoAllDefsSuite

class ThrowingDefsSuite:
  def allDefinitions: List[FakeSummary] =
    throw new RuntimeException("allDefinitions boom")

// Constructor with a side effect that fails — stands in for a suite that touches a
// backend at construction time.
class CtorBombSuite:
  throw new RuntimeException("constructor boom")

// Object whose static initializer fails: accessing MODULE$ raises
// ExceptionInInitializerError (an Error, not an Exception) — the shape a suite
// referencing embedded-Rift FFM classes takes on a JDK where FFM is unavailable.
object InitBombSuite:
  val boom: Int = throw new RuntimeException("static init boom")

object StepLoaderSpec extends ZIOSpecDefault:

  private val expected = List(("Given", "^a cart with (\\d+) items$", "a cart with {int} items"))

  def spec = suite("StepLoader")(
    test("extracts step definitions from a suite via reflection (MockSteps-shaped load path)") {
      assertTrue(StepLoader.stepsFor(classOf[GoodSuite]) == expected)
    },
    test("returns no steps when allDefinitions is absent (older zio-bdd, unchanged API path)") {
      assertTrue(StepLoader.stepsFor(classOf[NoAllDefsSuite]).isEmpty)
    },
    test("returns no steps when allDefinitions throws at call time") {
      assertTrue(StepLoader.stepsFor(classOf[ThrowingDefsSuite]).isEmpty)
    },
    test("reports failure (Left) when a suite constructor throws (side-effecting construction)") {
      assertTrue(StepLoader.instantiate(classOf[CtorBombSuite]).isLeft)
    },
    test("reports failure (Left) when static initialization fails with an Error (embedded FFM on wrong JDK)") {
      assertTrue(StepLoader.instantiate(classOf[InitBombSuite.type]).isLeft)
    },
    test("one failing suite — constructor, static-init Error, or throwing method — does not block the healthy ones") {
      val steps = StepLoader.stepsFromClasses(
        List(
          classOf[CtorBombSuite],
          classOf[InitBombSuite.type],
          classOf[GoodSuite],
          classOf[ThrowingDefsSuite]
        )
      )
      assertTrue(steps == expected)
    },
    // ── @mock catalog discovery (allMocks) ──────────────────────────────────
    test("extracts mock catalog entries (name, sourceKind) from a suite via reflection") {
      assertTrue(
        StepLoader.mocksFor(classOf[MockCatalogSuite]) ==
          List(("paymentGateway", "Json"), ("userService", "Dsl"))
      )
    },
    test("returns no mocks when allMocks is absent (a suite that isn't a MockSteps suite)") {
      assertTrue(StepLoader.mocksFor(classOf[GoodSuite]).isEmpty)
    },
    test("returns no mocks when allMocks throws at call time") {
      assertTrue(StepLoader.mocksFor(classOf[ThrowingMocksSuite]).isEmpty)
    },
    test("one failing suite does not block mock discovery for the healthy ones") {
      val mocks = StepLoader.mocksFromClasses(
        List(classOf[CtorBombSuite], classOf[MockCatalogSuite], classOf[ThrowingMocksSuite])
      )
      assertTrue(mocks == List(("paymentGateway", "Json"), ("userService", "Dsl")))
    },
    test("serialize emits a {steps,mocks} envelope carrying both step and mock entries") {
      val json = StepLoader.serialize(expected, List(("userService", "Dsl")))
      assertTrue(
        json.contains("\"steps\""),
        json.contains("\"mocks\""),
        // Braces are escaped as \uXXXX on the wire (#40), so the raw envelope
        // carries the escaped form, not the literal `{int}`.
        json.contains("\"a cart with \\u007bint\\u007d items\""),
        !json.contains("{int}"),
        json.contains("\"name\":\"userService\""),
        json.contains("\"sourceKind\":\"Dsl\"")
      )
    },
    test("serialize emits empty arrays (not []) when there is nothing to report") {
      assertTrue(StepLoader.serialize(Nil, Nil) == """{"steps":[],"mocks":[]}""")
    },
    test("summariesFor extracts steps and mocks from a single suite instantiation") {
      assertTrue(
        StepLoader.summariesFor(classOf[MockCatalogSuite]) ==
          (
            List(("Given", "^ready$", "ready")),
            List(("paymentGateway", "Json"), ("userService", "Dsl"))
          )
      )
    }
  )
