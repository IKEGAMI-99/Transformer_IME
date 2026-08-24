package com.ikegami.transformerime

import com.ikegami.transformerime.conversion.CandidateGenerator
import com.ikegami.transformerime.conversion.NextCandidateGenerator
import com.ikegami.transformerime.ime.FlickDirection
import com.ikegami.transformerime.ime.FlickKana
import com.ikegami.transformerime.model.MediumMoETransformer
import com.ikegami.transformerime.model.Zenzai190MEngine
import com.ikegami.transformerime.model.ZenzaiPrompt
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
        assertEquals("ゆ", FlickKana.output("や", FlickDirection.UP))
        assertEquals("よ", FlickKana.output("や", FlickDirection.DOWN))
        assertEquals("「", FlickKana.output("や", FlickDirection.LEFT))
        assertEquals("」", FlickKana.output("や", FlickDirection.RIGHT))
    }

    @Test
    fun kanaModifierPrioritizesSmallKanaBeforeDakuten() {
        assertEquals("が", FlickKana.modifyLast("か"))
        assertEquals("か", FlickKana.modifyLast("が"))
        assertEquals("ば", FlickKana.modifyLast("は"))
        assertEquals("ぱ", FlickKana.modifyLast("ば"))
        assertEquals("は", FlickKana.modifyLast("ぱ"))
        assertEquals("っ", FlickKana.modifyLast("つ"))
        assertEquals("づ", FlickKana.modifyLast("っ"))
        assertEquals("つ", FlickKana.modifyLast("づ"))
        assertEquals("ぅ", FlickKana.modifyLast("う"))
        assertEquals("ゔ", FlickKana.modifyLast("ぅ"))
        assertEquals("う", FlickKana.modifyLast("ゔ"))
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
    fun zenzaiV3PromptCarriesContextAndKatakanaReading() {
        val prompt = ZenzaiPrompt.conversion("今日は東京へ行く。", "しんじゅくえき")
        assertTrue(prompt.startsWith("\uEE02今日は東京へ行く。\uEE00"))
        assertTrue(prompt.contains("シンジュクエキ"))
        assertTrue(prompt.endsWith("\uEE01"))

        val predictionPrompt = ZenzaiPrompt.inputPrediction("よろしくお願いします")
        assertEquals("\uEE02よろしくお願いします\uEE00", predictionPrompt)
    }

    @Test
    fun threeAiSlotsSurviveDuplicateNeuralBranches() {
        val slots = Zenzai190MEngine.fillAiSlots(
            neural = listOf("新宿駅", "新宿駅"),
            drafts = listOf("新宿駅", "新宿", "新宿駅前", "しんじゅくえき"),
            count = 3
        )
        assertEquals(3, slots.size)
        assertEquals(3, slots.distinct().size)
        assertEquals("新宿駅", slots.first())
        assertTrue(slots.contains("新宿"))
    }

    @Test
    fun speculativeMergeUsesClassicalDraftAndNeuralConstraint() {
        val drafts = listOf("新宿駅", "新宿液", "新宿", "しんじゅくえき")

        val consensus = Zenzai190MEngine.mergeSpeculativeDrafts(
            drafts = drafts,
            primary = "新宿駅",
            fallback = "新宿駅",
            primaryMargin = 0.4f
        )
        assertEquals("新宿駅", consensus.first())

        val weakNovel = Zenzai190MEngine.mergeSpeculativeDrafts(
            drafts = drafts,
            primary = "新宿駅前",
            fallback = "",
            primaryMargin = 0.2f
        )
        assertEquals("新宿駅", weakNovel.first())
        assertTrue(weakNovel.contains("新宿駅前"))
    }

    @Test
    fun compatibilityAdapterFallsBackWithoutNativeAssets() {
        val model = MediumMoETransformer.create()
        val source = listOf("早く", "速く", "はやく")
        val result = model.rerank(
            contextText = "明日は仕事だから今日は",
            reading = "はやく",
            candidates = source
        )
        assertEquals(source, result.candidates)
        assertEquals(0L, result.latencyMs)
        assertEquals(0, model.parameterCount)
    }
}
