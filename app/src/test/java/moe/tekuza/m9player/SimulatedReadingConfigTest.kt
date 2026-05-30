package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Test

class SimulatedReadingConfigTest {
    @Test
    fun disabledConfigUnlocksAllRealChapters() {
        val config = SimulatedReadingConfig(
            enabled = false,
            startEpochDay = 100,
            startChapter = 1,
            dailyChapters = 1
        )

        assertEquals(12, simulatedReadingUnlockedChapterCount(config, realChapterCount = 12, todayEpochDay = 100))
    }

    @Test
    fun enabledConfigUnlocksFromStartChapterByElapsedDays() {
        val config = SimulatedReadingConfig(
            enabled = true,
            startEpochDay = 100,
            startChapter = 2,
            dailyChapters = 3
        )

        assertEquals(2, simulatedReadingUnlockedChapterCount(config, realChapterCount = 20, todayEpochDay = 100))
        assertEquals(8, simulatedReadingUnlockedChapterCount(config, realChapterCount = 20, todayEpochDay = 102))
    }

    @Test
    fun enabledConfigClampsFutureStartDateAndRealChapterCount() {
        val config = SimulatedReadingConfig(
            enabled = true,
            startEpochDay = 200,
            startChapter = 4,
            dailyChapters = 10
        )

        assertEquals(4, simulatedReadingUnlockedChapterCount(config, realChapterCount = 9, todayEpochDay = 190))
        assertEquals(9, simulatedReadingUnlockedChapterCount(config, realChapterCount = 9, todayEpochDay = 201))
    }
}
