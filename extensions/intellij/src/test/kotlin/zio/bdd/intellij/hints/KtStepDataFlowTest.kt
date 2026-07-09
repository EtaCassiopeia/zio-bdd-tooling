package zio.bdd.intellij.hints

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtStepDataFlowTest {

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
