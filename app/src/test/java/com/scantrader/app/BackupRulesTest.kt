package com.scantrader.app

import com.scantrader.app.data.catalog.CtGameCatalog
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class BackupRulesTest {
    private fun source(path: String): File =
        listOf(File(path), File("app/$path")).firstOrNull { it.isFile }
            ?: error("missing $path")

    private fun parse(path: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(source(path)).documentElement

    private fun rules(section: Element, tag: String): Set<Pair<String, String>> {
        val nodes = section.getElementsByTagName(tag)
        return (0 until nodes.length).map {
            val e = nodes.item(it) as Element
            e.getAttribute("domain") to e.getAttribute("path")
        }.toSet()
    }

    private val required = setOf(
        "sharedpref" to "scantrader_secrets.xml",
        "file" to "vision",
        "file" to "riftbound",
        "file" to "tracker",
        "database" to "androidx.work.workdb",
        "database" to "androidx.work.workdb-wal",
        "database" to "androidx.work.workdb-shm",
    ) + CtGameCatalog.GAME_IDS.keys.map { "file" to "catalogo-${it.csvLabel}" }

    private val kept = setOf(
        "database" to "scantrader.db",
        "database" to "scantrader.db-wal",
        "database" to "scantrader.db-shm",
        "file" to "datastore",
        "file" to "datastore/settings.preferences_pb",
    )

    private fun sections(): Map<String, Element> {
        val legacy = parse("src/main/res/xml/backup_rules.xml")
        val modern = parse("src/main/res/xml/data_extraction_rules.xml")
        fun child(tag: String) = modern.getElementsByTagName(tag).item(0) as Element
        return mapOf(
            "fullBackupContent" to legacy,
            "cloud-backup" to child("cloud-backup"),
            "device-transfer" to child("device-transfer"),
        )
    }

    @Test
    fun secretsPacksCatalogsTrackerAndWorkManagerStayOut() {
        for ((name, section) in sections()) {
            val excluded = rules(section, "exclude")
            assertEquals(name, emptySet<Pair<String, String>>(), required - excluded)
        }
    }

    @Test
    fun collectionAndSettingsAreBackedUp() {
        for ((name, section) in sections()) {
            // No <include>: everything not excluded is kept by default.
            assertTrue(name, rules(section, "include").isEmpty())
            val excluded = rules(section, "exclude")
            for (rule in kept) assertFalse("$name excludes $rule", rule in excluded)
        }
    }

    @Test
    fun allSectionsMatch() {
        val all = sections().values.map { rules(it, "exclude") }
        assertTrue(all.all { it == all.first() })
    }

    @Test
    fun manifestUsesBothRuleFiles() {
        val manifest = source("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }
}
