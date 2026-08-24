package com.ikegami.transformerime

import com.ikegami.transformerime.conversion.CandidateGenerator
import com.ikegami.transformerime.conversion.NextCandidateGenerator
import com.ikegami.transformerime.ime.FlickDirection
import com.ikegami.transformerime.ime.FlickKana
import com.ikegami.transformerime.model.MediumMoETransformer
import java.io.File
import java.io.FileInputStream
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
    fun japaneseFlickDirectionsMatchStandardVowels() {
        assertEquals("あ", FlickKana.output("あ", FlickDirection.CENTER))
        assertEquals("い", FlickKana.output("あ", FlickDirection.LEFT))
        assertEquals("う", FlickKana.output("あ", FlickDirection.UP))
        assertEquals("え", FlickKana.output("あ", FlickDirection.RIGHT))
        assertEquals("お", FlickKana.output("あ", FlickDirection.DOWN))
        assertEquals("こ", FlickKana.output("か", FlickDirection.DOWN))
        assertEquals("ん", FlickKana.output("わ", FlickDirection.UP))
    }

    @Test
    fun kanaModifierCyclesDakutenHandakutenAndSmallKana() {
        assertEquals("が", FlickKana.modifyLast("か"))
        assertEquals("か", FlickKana.modifyLast("が"))
        assertEquals("ば", FlickKana.modifyLast("は"))
        assertEquals("ぱ", FlickKana.modifyLast("ば"))
        assertEquals("は", FlickKana.modifyLast("ぱ"))
        assertEquals("っ", FlickKana.modifyLast("づ"))
        assertEquals("ゃ", FlickKana.modifyLast("や"))
    }

    @Test
    fun nextCandidatePoolUsesCommittedContext() {
        val afterYoroshiku = NextCandidateGenerator.candidates("確認しました。よろしく", emptyList())
        assertEquals("お願いします", afterYoroshiku.first())

        val afterThanks = NextCandidateGenerator.candidates("ありがとうございます", emptyList())
        assertTrue(afterThanks.take(4).contains("！"))
        assertTrue(afterThanks.contains("よろしくお願いします"))
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
    }

    @Test
    fun exportedJapaneseCorpusModelLoadsAndRuns() {
        val modelFile = listOf(
            File("app/src/main/assets/medium_moe_jpn.q8"),
            File("src/main/assets/medium_moe_jpn.q8")
        ).firstOrNull { it.exists() }
            ?: error("Generated Japanese model asset was not found")

        val model = FileInputStream(modelFile).use { MediumMoETransformer.loadQuantized(it) }
        assertTrue(model.corpusTrained)
        assertTrue(model.sourceLabel.contains("Tatoeba"))
        assertTrue("parameter count=${model.parameterCount}", model.parameterCount >= 5_000_000)

        val source = listOf("良い感じだと思う", "いい感じだと思う", "良い感じだとおもう")
        val result = model.rerank(
            contextText = "これは",
            reading = "いいかんじだとおもう",
            candidates = source
        )
        assertEquals(source.toSet(), result.candidates.toSet())
        assertTrue(result.latencyMs >= 0)
    }
}
