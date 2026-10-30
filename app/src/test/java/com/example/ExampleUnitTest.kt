package com.example

import com.example.data.Flashcard
import com.example.data.FlashcardTypes
import com.example.data.ReviewGrade
import com.example.data.SpacedRepetitionUtils
import com.example.data.SrsStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun testStageProgression() {
        // Stage 0 -> Good -> Stage 1
        val nextGood = SpacedRepetitionUtils.calculateNextStage(0, ReviewGrade.GOOD)
        assertEquals(1, nextGood)

        // Stage 0 -> Easy -> Stage 2
        val nextEasy = SpacedRepetitionUtils.calculateNextStage(0, ReviewGrade.EASY)
        assertEquals(2, nextEasy)

        // Stage 3 -> Hard -> Stage 3
        val nextHard = SpacedRepetitionUtils.calculateNextStage(3, ReviewGrade.HARD)
        assertEquals(3, nextHard)

        // Stage 4 -> Again -> Stage 0
        val nextAgain = SpacedRepetitionUtils.calculateNextStage(4, ReviewGrade.AGAIN)
        assertEquals(0, nextAgain)
    }

    @Test
    fun testStageCount() {
        assertEquals(8, SrsStage.entries.size)
        assertEquals("2 Minutes", SrsStage.TWO_MINUTES.title)
        assertEquals("2m", SrsStage.TWO_MINUTES.shortName)
        assertEquals("1 Month", SrsStage.ONE_MONTH.title)
        assertEquals("30d", SrsStage.ONE_MONTH.shortName)
    }

    @Test
    fun testStageProgressionClampsInvalidIndices() {
        assertEquals(0, SpacedRepetitionUtils.calculateNextStage(-10, ReviewGrade.HARD))
        assertEquals(1, SpacedRepetitionUtils.calculateNextStage(-10, ReviewGrade.GOOD))
        assertEquals(2, SpacedRepetitionUtils.calculateNextStage(-10, ReviewGrade.EASY))
        assertEquals(7, SpacedRepetitionUtils.calculateNextStage(99, ReviewGrade.GOOD))
        assertEquals(7, SpacedRepetitionUtils.calculateNextStage(99, ReviewGrade.EASY))
    }

    @Test
    fun testUpcomingSixDays() {
        val now = System.currentTimeMillis()
        val cards = listOf(
            Flashcard(
                type = FlashcardTypes.SPELLING,
                word = "TestWord1",
                stageIndex = 0,
                nextReviewTimeMillis = now - 5000L
            ),
            Flashcard(
                type = FlashcardTypes.PRONUNCIATION,
                word = "TestWord2",
                stageIndex = 1,
                nextReviewTimeMillis = now + 10000L
            )
        )
        val days = SpacedRepetitionUtils.getUpcomingSixDays(cards)
        assertEquals(6, days.size)
        assertEquals("Today", days[0].dayLabel)
        assertTrue(days[0].count >= 2)
    }
}
