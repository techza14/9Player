package moe.tekuza.m9player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StatisticsDemoNavigationTest {
    @Test
    fun settingsAdvancedSectionOpensStatisticsDemo() {
        val settingsPanelSource = File("src/main/java/moe/tekuza/m9player/SettingsPanel.kt").readText()
        val mainActivitySource = File("src/main/java/moe/tekuza/m9player/MainActivity.kt").readText()
        val manifestSource = File("src/main/AndroidManifest.xml").readText()
        val statisticsFile = File("src/main/java/moe/tekuza/m9player/StatisticsDemoActivity.kt")
        val stringsSource = File("src/main/res/values/strings.xml").readText()

        assertTrue(statisticsFile.isFile)
        val statisticsSource = statisticsFile.readText()
        assertTrue(settingsPanelSource.contains("onAdvancedStatisticsClick: () -> Unit"))
        assertTrue(settingsPanelSource.contains("settings_statistics_title"))
        assertTrue(mainActivitySource.contains("StatisticsDemoActivity::class.java"))
        assertTrue(manifestSource.contains("android:name=\".StatisticsDemoActivity\""))
        assertTrue(stringsSource.contains("settings_statistics_title"))
        assertTrue(statisticsSource.contains("StatisticsDemoActivity"))
        assertTrue(statisticsSource.contains("StatisticsSummaryUi"))
        assertTrue(statisticsSource.contains("BookComparisonSelection"))
        assertTrue(statisticsSource.contains("var selectedBook by remember { mutableStateOf<BookStatisticsUi?>(null) }"))
        assertTrue(statisticsSource.contains("var comparisonBook by remember { mutableStateOf<BookStatisticsUi?>(null) }"))
        assertTrue(statisticsSource.contains("BookSelectionDialog"))
        assertTrue(statisticsSource.contains("EmptyComparisonSelectionCard"))
        assertTrue(statisticsSource.contains("SelectedBookOnlyPanel"))
        assertTrue(statisticsSource.contains("SelectedBookStats"))
        assertTrue(statisticsSource.contains("rememberLazyListState"))
        assertTrue(statisticsSource.contains("LaunchedEffect(selectionScrollRequest)"))
        assertTrue(statisticsSource.contains("listState.animateScrollToItem"))
        assertTrue(statisticsSource.contains("statistics_select_audiobook"))
        assertTrue(statisticsSource.contains("statistics_compare"))
        assertTrue(statisticsSource.contains("onPrimaryBookClick"))
        assertTrue(statisticsSource.contains("onComparisonBookClick"))
        assertTrue(!statisticsSource.contains("ComparisonMetricRow(\"进度\""))
        assertTrue(statisticsSource.contains("statistics_text_density"))
        assertTrue(statisticsSource.contains("statistics_listening_speed"))
        assertTrue(statisticsSource.contains("statistics_progress_speed"))
        assertTrue(statisticsSource.contains("ComparisonMetricRow"))
    }
}
