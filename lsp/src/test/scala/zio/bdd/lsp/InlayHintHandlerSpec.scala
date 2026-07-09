package zio.bdd.lsp

import zio.bdd.lsp.handlers.InlayHintHandler
import zio.test.*

object InlayHintHandlerSpec extends ZIOSpecDefault:
  import DataRef.*

  private def flow(reads: Set[DataRef], sets: Set[DataRef]) = DataFlow(reads, sets)

  def spec = suite("InlayHintHandler.scenarioLabels / labelFor")(
    test("a read points back to the nearest earlier step that set it") {
      val labels = InlayHintHandler.scenarioLabels(
        List(
          flow(Set.empty, Set(StageType("OrderId"))), // step 1 sets Stage[OrderId]
          flow(Set(StageType("OrderId")), Set.empty)  // step 2 reads it
        )
      )
      assertTrue(
        labels(0).contains("sets Stage[OrderId]"),
        labels(1).contains("reads Stage[OrderId] (← set by step 1)")
      )
    },
    test("a read with no earlier producer has no back-reference") {
      val labels = InlayHintHandler.scenarioLabels(List(flow(Set(StageType("Bar")), Set.empty)))
      assertTrue(labels(0).contains("reads Stage[Bar]"), !labels(0).exists(_.contains("←")))
    },
    test("one step's reads can point at several different producers") {
      val labels = InlayHintHandler.scenarioLabels(
        List(
          flow(Set.empty, Set(StageType("A"))),                 // step 1
          flow(Set.empty, Set(StateField("x"))),                // step 2
          flow(Set(StageType("A"), StateField("x")), Set.empty) // step 3 reads both
        )
      )
      // sorted case-insensitively: "Stage[A]" before "x"
      assertTrue(labels(2).contains("reads Stage[A] (← set by step 1), x (← set by step 2)"))
    },
    test("read-modify-write: the read points at the prior producer, not this step") {
      val labels = InlayHintHandler.scenarioLabels(
        List(
          flow(Set.empty, Set(StateField("count"))),                // step 1 sets count
          flow(Set(StateField("count")), Set(StateField("count"))), // step 2 reads+writes count
          flow(Set(StateField("count")), Set.empty)                 // step 3 reads count
        )
      )
      assertTrue(
        labels(1).contains("sets count · reads count (← set by step 1)"),
        labels(2).contains("reads count (← set by step 2)")
      )
    },
    test("sets are listed without back-references and an empty flow yields no label") {
      assertTrue(
        InlayHintHandler
          .labelFor(flow(Set.empty, Set(StateField("a"), StageType("Z"))), Map.empty)
          .contains("sets a, Stage[Z]"),
        InlayHintHandler.labelFor(DataFlow.empty, Map.empty).isEmpty
      )
    }
  )
