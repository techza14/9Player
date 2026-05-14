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
        assertTrue(settingsPanelSource.contains("settings_statistics_subtitle"))
        assertTrue(mainActivitySource.contains("StatisticsDemoActivity::class.java"))
        assertTrue(manifestSource.contains("android:name=\".StatisticsDemoActivity\""))
        assertTrue(stringsSource.contains("settings_statistics_title"))
        assertTrue(statisticsSource.contains("StatisticsDemoActivity"))
        assertTrue(statisticsSource.contains("DemoStatisticsSummary"))
        assertTrue(statisticsSource.contains("DemoComparisonSelection"))
        assertTrue(statisticsSource.contains("var selectedBook by remember { mutableStateOf<DemoBookStatistics?>(null) }"))
        assertTrue(statisticsSource.contains("var comparisonBook by remember { mutableStateOf<DemoBookStatistics?>(null) }"))
        assertTrue(statisticsSource.contains("BookSelectionDialog"))
        assertTrue(statisticsSource.contains("EmptyComparisonSelectionCard"))
        assertTrue(statisticsSource.contains("SelectedBookOnlyPanel"))
        assertTrue(statisticsSource.contains("SelectedBookStats"))
        assertTrue(statisticsSource.contains("rememberLazyListState"))
        assertTrue(statisticsSource.contains("LaunchedEffect(selectedBook, comparisonBook)"))
        assertTrue(statisticsSource.contains("listState.animateScrollToItem"))
        assertTrue(statisticsSource.contains("选择有声书"))
        assertTrue(statisticsSource.contains("Text(\"对比\")"))
        assertTrue(statisticsSource.contains("onPrimaryBookClick"))
        assertTrue(statisticsSource.contains("onComparisonBookClick"))
        assertTrue(!statisticsSource.contains("ComparisonMetricRow(\"进度\""))
        assertTrue(statisticsSource.contains("文本密度"))
        assertTrue(statisticsSource.contains("消化速度"))
        assertTrue(statisticsSource.contains("进度速度"))
        assertTrue(statisticsSource.contains("ComparisonMetricRow"))
    }
}
