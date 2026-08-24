package com.ikegami.transformerime

import com.ikegami.transformerime.conversion.CandidateGenerator
import com.ikegami.transformerime.model.MediumMoETransformer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversionAndModelTest {
    @Test
    fun multiSegmentKanaKanjiConversion() {
        val candidates = CandidateGenerator.candidates("きょうはてんきがいい")
        assertTrue(candidates.isNotEmpty())
        assertEquals("今日は天気が良い", candidates.first())
        assertTrue(candidates.contains("今日は天気がいい"))
    }

    @Test
    fun mediumModelHasFiveMillionParametersAndRunsInference() {
        val model = MediumMoETransformer.create()
        assertTrue("parameter count=${model.parameterCount}", model.parameterCount >= 5_000_000)

        val source = listOf("早く", "速く", "はやく")
        val result = model.rerank(
            contextText = "明日は仕事だから今日は",
            reading = "はやく",
            candidates = source
        )

        assertEquals(source.toSet(), result.candidates.toSet())
        assertEquals(source.size, result.candidates.size)
        assertTrue(result.latencyMs >= 0)
        println("MediumMoE params=${model.parameterCount}, JVM latency=${result.latencyMs}ms, ranked=${result.candidates}")
    }
}
