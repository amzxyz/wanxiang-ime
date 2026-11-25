package org.fcitx.directrime

import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real compiled Prism 7.0 and JNI code-choice contract. No keymap. */
class OrderedSpellingTabSdkTest {
    @Test fun strictSubsetSelectionUsesOriginalInput(): Unit = runBlocking {
        exercise(
            listOf("derive/^(.*)$/\\U${'$'}1/", "xlit/ABCDEFGHIJKLMNOPQRSTUVWXYZ/22233344455566677778889999/"),
            "986", "zuo", setOf("巡", "云", "尊", "做", "左"), setOf("做", "左"),
            setOf("巡", "云", "尊"), listOf("xun", "yun", "zun", "zuo")
        )
    }

    @Test fun differentLengthNonNumericChoice(): Unit = runBlocking {
        exercise(
            listOf("derive/^xun$/mix/", "derive/^yun$/mix/", "derive/^xun$/q/"),
            "mix", "q", setOf("巡", "云"), setOf("巡"), setOf("云"), null
        )
    }

    @Test fun equalSetsDoNotProduceChoices(): Unit = runBlocking {
        exercise(listOf("derive/^xun$/alias/"), "alias", null, setOf("巡"),
            emptySet(), emptySet(), emptyList())
    }

    private suspend fun exercise(
        algebra: List<String>, keys: String, label: String?,
        before: Set<String>, retained: Set<String>, excluded: Set<String>,
        expectedTabs: List<String>?
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rules = algebra.joinToString("\n") { "            - $it" }
        val files = mapOf(
            "default.yaml" to "config_version: '1.0'\nschema_list:\n  - schema: sdk_ordered_tabs\n",
            "sdk_ordered_tabs.schema.yaml" to """
                schema:
                  schema_id: sdk_ordered_tabs
                  name: Ordered spelling SDK test
                  version: '1.0'
                engine:
                  processors: [speller]
                  segmentors: [abc_segmentor]
                  translators: [script_translator]
                speller:
                  alphabet: 'abcdefghijklmnopqrstuvwxyz23456789'
                  delimiter: " '"
                translator:
                  dictionary: sdk_ordered_tabs
                  prism: choices
                  enable_user_dict: false
                  enable_completion: false
                build:
                  dictionaries:
                    sdk_ordered_tabs:
                      algebra:
                        choices:
            """.trimIndent() + "\n" + rules + "\nmenu:\n  page_size: 20\n",
            "sdk_ordered_tabs.dict.yaml" to
                "---\nname: sdk_ordered_tabs\nversion: '1.0'\nsort: by_weight\n...\n" +
                "巡\txun\t100\n云\tyun\t100\n尊\tzun\t100\n做\tzuo\t100\n左\tzuo\t90\n"
        )
        val profile = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                files.forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        try {
            assertTrue("The EVO profile must deploy", RimeRuntime.deploy(
                context, profile = { ByteArrayInputStream(profile) }
            ))
            val generation = RimeRuntime.newGeneration()
            var state = RimeRuntime.begin(context, generation, "sdk.code-choice")!!
            assertTrue(state.gateMessage, state.ready)
            assertEquals("sdk_ordered_tabs", state.schema)
            keys.forEach { state = RimeRuntime.key(generation, it.code)!! }
            before.forEach { candidate ->
                assertTrue("Before selection: ${state.candidates.contentToString()}",
                    candidate in state.candidates)
            }
            expectedTabs?.let { assertEquals(it.toSet(), state.tabs.toSet()) }
            if (label != null) {
                val tab = state.tabs.indexOf(label)
                assertTrue("Missing $label: ${state.tabs.contentToString()}", tab >= 0)
                state = RimeRuntime.action(state, 4, tab)!!
                assertTrue(state.gateMessage, state.ready)
                assertTrue(state.commit.isEmpty())
                retained.forEach { assertTrue(it in state.candidates) }
                excluded.forEach { assertFalse(it in state.candidates) }
            }
            RimeRuntime.end(generation)
        } finally {
            assertTrue(RimeRuntime.deploy(context, smoke = true))
        }
    }
}
