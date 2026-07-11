package zio.bdd.intellij.hints

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtStepDataFlowTest {

    @Test
    fun readsStateFieldViaNamedFlatMapBinder() {
        val df = KtStepDataFlow.analyze(
            "ScenarioContext.get.flatMap { state => Assertions.assertEquals(state.inputPath, expected) }",
        )
        assertEquals(setOf(DataRef.StateField("inputPath")), df.reads)
        assertTrue(df.sets.isEmpty())
    }

    @Test
    fun readsStateFieldViaForComprehensionBinder() {
        val df = KtStepDataFlow.analyze("for { s <- ScenarioContext.get } yield s.validRows.length")
        assertEquals(setOf(DataRef.StateField("validRows")), df.reads)
    }

    @Test
    fun readsStateFieldViaUnderscorePlaceholder() {
        val df = KtStepDataFlow.analyze("ScenarioContext.get.map(_.count)")
        assertEquals(setOf(DataRef.StateField("count")), df.reads)
    }

    @Test
    fun placeholderTreatsCopyAsWriteNotReadOfFieldNamedCopy() {
        val df = KtStepDataFlow.analyze("ScenarioContext.get.map(_.copy(count = 1))")
        assertTrue(df.reads.isEmpty())
        assertEquals(setOf(DataRef.StateField("count")), df.sets)
    }

    @Test
    fun binderReadScanDoesNotLeakSimilarNamesOrCopy() {
        val df = KtStepDataFlow.analyze(
            "ScenarioContext.get.flatMap(s => ScenarioContext.set(s.copy(total = results.total)))",
        )
        assertTrue(df.reads.isEmpty())
        assertEquals(setOf(DataRef.StateField("total")), df.sets)
    }

    @Test
    fun aFieldReadAndWrittenAppearsInBoth() {
        val df = KtStepDataFlow.analyze(
            "ScenarioContext.get.flatMap(s => ScenarioContext.update(_.copy(count = s.count + 1)))",
        )
        assertTrue(df.reads.contains(DataRef.StateField("count")))
        assertTrue(df.sets.contains(DataRef.StateField("count")))
    }

    @Test
    fun nestedAndChainedCopySetsCaptureEveryField() {
        val df = KtStepDataFlow.analyze("_.copy(a = 1).copy(inner = inner.copy(b = 2))")
        assertEquals(setOf(DataRef.StateField("a"), DataRef.StateField("inner"), DataRef.StateField("b")), df.sets)
    }

    @Test
    fun stepCallingASameFileHelperInheritsItsReadsAndSets() {
        val helpers = KtStepDataFlow.resolveHelpers(
            """
            |  protected def lastResponse: IO[Throwable, LastResponse] =
            |    Stage.get[LastResponse].mapError(e => new IllegalStateException("no response"))
            |  protected def pendingRequest: UIO[PendingRequest] =
            |    Stage.get[PendingRequest].orElseSucceed(PendingRequest())
            """.trimMargin(),
        )
        val readStep = KtStepDataFlow.analyze("lastResponse.flatMap(r => assertRepro(r.status == code))", helpers)
        val setStep = KtStepDataFlow.analyze("pendingRequest.flatMap(pr => Stage.put(pr.copy(body = Some(b))))", helpers)
        assertEquals(setOf(DataRef.StageType("LastResponse")), readStep.reads)
        assertEquals(setOf(DataRef.StageType("PendingRequest")), setStep.reads)
        assertTrue(setStep.sets.contains(DataRef.StateField("body")))
    }

    @Test
    fun helperResolutionIsTransitiveAndCycleSafe() {
        val helpers = KtStepDataFlow.resolveHelpers(
            """
            |  def a: X = b *> Stage.put(Alpha())
            |  def b: X = a *> Stage.get[Beta]
            """.trimMargin(),
        )
        assertTrue(helpers.getValue("a").sets.contains(DataRef.StageType("Alpha")))
        assertTrue(helpers.getValue("a").reads.contains(DataRef.StageType("Beta")))
    }

    @Test
    fun aValLocalToAStepBodyDoesNotSweepUpOtherStagesCalls() {
        val helpers = KtStepDataFlow.resolveHelpers(
            """
            |  Given("prepare") { _ =>
            |    val body = readBody()
            |    Stage.get[RawPayload]
            |  }
            """.trimMargin(),
        )
        val df = KtStepDataFlow.analyze("ScenarioContext.update(_.copy(body = fresh))", helpers)
        assertTrue(!df.reads.contains(DataRef.StageType("RawPayload")))
        assertEquals(setOf(DataRef.StateField("body")), df.sets)
    }

    @Test
    fun aFieldAccessWithAHelpersNameIsNotAHelperCall() {
        val helpers = KtStepDataFlow.resolveHelpers("  def status: UIO[S] = Stage.get[Status]")
        val df = KtStepDataFlow.analyze("lastResponse.flatMap(r => check(r.status))", helpers)
        assertTrue(!df.reads.contains(DataRef.StageType("Status")))
    }

    @Test
    fun detectsMultipleStateFieldSetsFromOneCopy() {
        val df = KtStepDataFlow.analyze("ScenarioContext.update(_.copy(result = a + b, failed = false))")
        assertEquals(setOf(DataRef.StateField("result"), DataRef.StateField("failed")), df.sets)
        assertTrue(df.reads.isEmpty())
    }

    @Test
    fun detectsStagePutAndGetTogether() {
        val df = KtStepDataFlow.analyze("for { _ <- Stage.put(FooEvent(x)); e <- Stage.get[BarEvent] } yield e")
        assertEquals(setOf(DataRef.StageType("FooEvent")), df.sets)
        assertEquals(setOf(DataRef.StageType("BarEvent")), df.reads)
    }

    @Test
    fun detectsStateAndStageSetsInTheSameStep() {
        // A single step can set several values across BOTH State and Stage.
        val df = KtStepDataFlow.analyze("Stage.put(Widget(1)); ScenarioContext.update(_.copy(count = 1, name = n))")
        assertEquals(
            setOf(DataRef.StageType("Widget"), DataRef.StateField("count"), DataRef.StateField("name")),
            df.sets,
        )
    }

    @Test
    fun honorsExplicitStagePutType() {
        assertEquals(setOf(DataRef.StageType("Foo")), KtStepDataFlow.analyze("Stage.put[Foo](make())").sets)
    }

    @Test
    fun handlesQualifiedStageTypeAndGetOrElse() {
        val df = KtStepDataFlow.analyze("Stage.put(NativeSpec.Rift(json)) *> Stage.getOrElse[Space](build)")
        assertEquals(setOf(DataRef.StageType("NativeSpec.Rift")), df.sets)
        assertEquals(setOf(DataRef.StageType("Space")), df.reads)
    }

    @Test
    fun dedupsRepeatedRefs() {
        val df = KtStepDataFlow.analyze("ScenarioContext.update(_.copy(a = 1)) *> ScenarioContext.update(_.copy(a = 2))")
        assertEquals(setOf(DataRef.StateField("a")), df.sets)
    }

    @Test
    fun lensGetIsAReadAndLensUpdateIsASet() {
        val get = KtStepDataFlow.analyze("ScenarioLens.get[State, Balance](ctx)")
        assertEquals(setOf(DataRef.LensSlice("Balance")), get.reads)
        assertTrue(get.sets.isEmpty())
        val upd = KtStepDataFlow.analyze("ScenarioLens.update[State, Balance](ctx)(f)")
        assertEquals(setOf(DataRef.LensSlice("Balance")), upd.sets)
        assertTrue(upd.reads.isEmpty())
    }

    @Test
    fun copyWithNestedParensKeepsAllFields() {
        val df = KtStepDataFlow.analyze("_.copy(result = compute(a, b), failed = false)")
        assertEquals(setOf(DataRef.StateField("result"), DataRef.StateField("failed")), df.sets)
    }

    @Test
    fun copyDoesNotTreatEqualityOrNamedArgsAsFields() {
        // `a == b` must not yield a field `a`; a nested named arg `w = 1` must not leak.
        val df = KtStepDataFlow.analyze("_.copy(ready = a == b, size = make(w = 1))")
        assertEquals(setOf(DataRef.StateField("ready"), DataRef.StateField("size")), df.sets)
    }

    @Test
    fun copyDoesNotLeakLambdaArrowParamAsField() {
        val df = KtStepDataFlow.analyze("_.copy(onDone = k => k, count = 1)")
        assertEquals(setOf(DataRef.StateField("onDone"), DataRef.StateField("count")), df.sets)
    }

    @Test
    fun stageGetOptionIsARead() {
        assertEquals(setOf(DataRef.StageType("Payload")), KtStepDataFlow.analyze("Stage.getOption[Payload].map(f)").reads)
    }

    @Test
    fun emptyForABodyWithNoStateOrStage() {
        assertTrue(KtStepDataFlow.analyze("ZIO.unit").isEmpty())
    }

    @Test
    fun rendersSetsAndReadsAndNullWhenEmpty() {
        val df = KtStepDataFlow.analyze("Stage.put(Foo(x)); ScenarioContext.update(_.copy(a = 1)) *> Stage.get[Bar]")
        assertEquals("sets a, Stage[Foo] · reads Stage[Bar]", df.render())
        assertEquals(null, StepDataFlow.EMPTY.render())
    }

    @Test
    fun bodyAtExtractsTheStepBodyForAnalysis() {
        val src =
            "object CalcSteps extends ZIOSteps[Any, S] {\n" +
                "  Given(\"a fresh calculator\") { ScenarioContext.update(_.copy(result = 0)) }\n" +
                "  When(\"I add \" / int / \" and \" / int) { (a: Int, b: Int) => ScenarioContext.update(_.copy(result = a + b)) }\n" +
                "}\n"
        // 'When' step is on 0-based line 2.
        val body = KtStepDataFlow.bodyAt(src, 2)
        assertTrue("got: $body", body != null && body.contains("result = a + b"))
        assertEquals(setOf(DataRef.StateField("result")), KtStepDataFlow.analyze(body!!).sets)
    }
}
