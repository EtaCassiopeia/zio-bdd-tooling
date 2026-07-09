package zio.bdd.lsp

import zio.test.*

object DataFlowSpec extends ZIOSpecDefault:
  import DataRef.*

  def spec = suite("StepDataFlow.analyze")(
    test("state field reads via a named flatMap binder") {
      val df = StepDataFlow.analyze(
        "ScenarioContext.get.flatMap { state => Assertions.assertEquals(state.inputPath, expected) }"
      )
      assertTrue(df.reads == Set[DataRef](StateField("inputPath")), df.sets.isEmpty)
    },
    test("state field reads via a for-comprehension binder") {
      val df = StepDataFlow.analyze("for { s <- ScenarioContext.get } yield s.validRows.length")
      assertTrue(df.reads == Set[DataRef](StateField("validRows")))
    },
    test("state field read via an underscore placeholder") {
      val df = StepDataFlow.analyze("ScenarioContext.get.map(_.count)")
      assertTrue(df.reads == Set[DataRef](StateField("count")))
    },
    test("placeholder path treats _.copy as a write, not a read of a field named copy") {
      val df = StepDataFlow.analyze("ScenarioContext.get.map(_.copy(count = 1))")
      assertTrue(df.reads.isEmpty, df.sets == Set[DataRef](StateField("count")))
    },
    test("binder field-read scan does not leak similarly-named identifiers or .copy") {
      // binder is `s`; `results.total` must not leak (no word boundary before the final s of
      // `results`), and `s.copy(...)` is a write not a read (field `copy` excluded).
      val df = StepDataFlow.analyze(
        "ScenarioContext.get.flatMap(s => ScenarioContext.set(s.copy(total = results.total)))"
      )
      assertTrue(df.reads.isEmpty, df.sets == Set[DataRef](StateField("total")))
    },
    test("a field both read and written appears in reads and sets") {
      val df = StepDataFlow.analyze(
        "ScenarioContext.get.flatMap(s => ScenarioContext.update(_.copy(count = s.count + 1)))"
      )
      assertTrue(df.reads.contains(StateField("count")), df.sets.contains(StateField("count")))
    },
    test("nested and chained .copy sets capture every field") {
      val df = StepDataFlow.analyze("_.copy(a = 1).copy(inner = inner.copy(b = 2))")
      assertTrue(df.sets == Set[DataRef](StateField("a"), StateField("inner"), StateField("b")))
    },
    test("multiple state field sets from one .copy") {
      val df = StepDataFlow.analyze("ScenarioContext.update(_.copy(result = a + b, failed = false))")
      assertTrue(
        df.sets == Set[DataRef](StateField("result"), StateField("failed")),
        df.reads.isEmpty
      )
    },
    test("Stage.put and Stage.get together") {
      val df = StepDataFlow.analyze("for { _ <- Stage.put(FooEvent(x)); e <- Stage.get[BarEvent] } yield e")
      assertTrue(
        df.sets == Set[DataRef](StageType("FooEvent")),
        df.reads == Set[DataRef](StageType("BarEvent"))
      )
    },
    test("State and Stage sets in the same step") {
      val df = StepDataFlow.analyze("Stage.put(Widget(1)); ScenarioContext.update(_.copy(count = 1, name = n))")
      assertTrue(
        df.sets == Set[DataRef](StageType("Widget"), StateField("count"), StateField("name"))
      )
    },
    test("explicit Stage.put type arg wins") {
      assertTrue(StepDataFlow.analyze("Stage.put[Foo](make())").sets == Set[DataRef](StageType("Foo")))
    },
    test("qualified Stage type and getOrElse") {
      val df = StepDataFlow.analyze("Stage.put(NativeSpec.Rift(json)) *> Stage.getOrElse[Space](build)")
      assertTrue(
        df.sets == Set[DataRef](StageType("NativeSpec.Rift")),
        df.reads == Set[DataRef](StageType("Space"))
      )
    },
    test("Stage.getOption is a read") {
      assertTrue(StepDataFlow.analyze("Stage.getOption[Payload].map(f)").reads == Set[DataRef](StageType("Payload")))
    },
    test("ScenarioLens.get is a read; update/set are writes") {
      val get = StepDataFlow.analyze("ScenarioLens.get[State, Balance](ctx)")
      val upd = StepDataFlow.analyze("ScenarioLens.update[State, Balance](ctx)(f)")
      val set = StepDataFlow.analyze("ScenarioLens.set[State, Balance](ctx)(v)")
      assertTrue(
        get.reads == Set[DataRef](LensSlice("Balance")),
        get.sets.isEmpty,
        upd.sets == Set[DataRef](LensSlice("Balance")),
        set.sets == Set[DataRef](LensSlice("Balance"))
      )
    },
    test(".copy with nested parens keeps all fields") {
      val df = StepDataFlow.analyze("_.copy(result = compute(a, b), failed = false)")
      assertTrue(df.sets == Set[DataRef](StateField("result"), StateField("failed")))
    },
    test(".copy does not treat == equality or nested named args as fields") {
      val df = StepDataFlow.analyze("_.copy(ready = a == b, size = make(w = 1))")
      assertTrue(df.sets == Set[DataRef](StateField("ready"), StateField("size")))
    },
    test(".copy does not leak a lambda arrow parameter as a field") {
      val df = StepDataFlow.analyze("_.copy(onDone = k => k, count = 1)")
      assertTrue(df.sets == Set[DataRef](StateField("onDone"), StateField("count")))
    },
    test("repeated refs are deduped") {
      val df = StepDataFlow.analyze("ScenarioContext.update(_.copy(a = 1)) *> ScenarioContext.update(_.copy(a = 2))")
      assertTrue(df.sets == Set[DataRef](StateField("a")))
    },
    test("empty for a body with no State or Stage") {
      assertTrue(StepDataFlow.analyze("ZIO.unit").isEmpty)
    },
    test("render lists sets then reads, sorted case-insensitively; None when empty") {
      val df = StepDataFlow.analyze("Stage.put(Foo(x)); ScenarioContext.update(_.copy(a = 1)) *> Stage.get[Bar]")
      assertTrue(
        df.render.contains("sets a, Stage[Foo] · reads Stage[Bar]"),
        DataFlow.empty.render.isEmpty
      )
    }
  )
