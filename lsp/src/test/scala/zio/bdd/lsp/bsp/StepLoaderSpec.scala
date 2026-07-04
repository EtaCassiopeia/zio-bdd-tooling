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

class GoodSuite:
  def allDefinitions: List[FakeSummary] =
    List(FakeSummary("Given", "^a cart with (\\d+) items$", "a cart with {int} items"))

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
    }
  )
