/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RimeInputTypesRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    private val schema = """
        schema:
          schema_id: wanxiang
          name: 万象拼音
        menu:
          page_size: 5
        input_types:
          enabled: [zrm] #启用哪些build就部署哪些
          reset: zrm
          options:
            - {option: pinyin, label: 全拼}
            - {option: zrm, label: 自然码}
            - {option: flypy, label: 小鹤双拼}
        translator:
          dictionary: wanxiang
    """.trimIndent() + "\n"

    private val custom = """
        # 本文件的工作目录是在用户根目录
        patch:
          # 启用清单决定可部署、可切换的输入类型
          input_types/enabled: [zrm]
          input_types/reset: zrm
          menu/page_size: 6
    """.trimIndent() + "\n"

    private fun user(schemaText: String = schema, customText: String? = custom): File {
        val dir = temp.newFolder()
        File(dir, "wanxiang.schema.yaml").writeText(schemaText)
        if (customText != null) File(dir, "wanxiang.custom.yaml").writeText(customText)
        return dir
    }

    @Test fun readsDeclaredTypesAndAppliesThePatch() {
        val dir = user()
        val types = requireNotNull(RimeInputTypesRepository.read(dir))

        assertEquals(listOf("pinyin", "zrm", "flypy"), types.types.map { it.option })
        assertEquals(listOf("全拼", "自然码", "小鹤双拼"), types.types.map { it.label })
        assertEquals(listOf("zrm"), types.enabled)
        assertEquals("zrm", types.reset)
        assertEquals("自然码", types.current?.label)
        assertEquals("自然码", types.summary())
    }

    @Test fun fallsBackToSchemaWhenThePatchIsMissing() {
        val dir = user(customText = null)
        val types = requireNotNull(RimeInputTypesRepository.read(dir))

        assertEquals(listOf("zrm"), types.enabled)
        assertEquals("zrm", types.reset)
        assertNull(RimeInputTypesRepository.read(temp.newFolder("empty")))
    }

    @Test fun unknownTypesAreDroppedFromTheEffectiveSelection() {
        val dir = user(customText = "patch:\n  input_types/enabled: [dnsp]\n  input_types/reset: dnsp\n")
        val types = requireNotNull(RimeInputTypesRepository.read(dir))

        // dnsp is not in options, so the declaration falls back to nothing usable.
        assertTrue(types.enabled.isEmpty())
        assertNull(types.reset)
        assertNull(types.current)
    }

    @Test fun readBindsToTheActiveSchemaInsteadOfTheFirstFile() {
        val dir = user()
        File(dir, "english.schema.yaml").writeText(
            schema.replace("wanxiang", "english").replace("自然码", "英文自然码")
        )
        File(dir, "english.custom.yaml").writeText("patch:\n  input_types/enabled: [flypy]\n  input_types/reset: flypy\n")

        val english = requireNotNull(RimeInputTypesRepository.read(dir, "english"))
        assertEquals("english", english.schemaId)
        assertEquals("小鹤双拼", english.summary())
        assertEquals(listOf("flypy"), english.enabled)
        assertEquals("english.custom.yaml", english.customFile.name)

        // An id that is not a plain schema file name never escapes the user directory.
        assertNull(RimeInputTypesRepository.read(dir, "../wanxiang"))
    }

    @Test fun wholeGroupPatchIsAppliedBeforeChildPaths() {
        // Rime sorts patch keys: "input_types" precedes "input_types/options/@0/option".
        // The group replaces the schema block first, then the child path edits its options.
        val dir = user(
            customText = "patch:\n" +
                "  input_types/options/@0/option: removed_pinyin\n" +
                "  input_types:\n" +
                "    enabled: [zrm]\n" +
                "    reset: zrm\n" +
                "    options:\n" +
                "      - {option: pinyin, label: 全拼}\n" +
                "      - {option: zrm, label: 自然码}\n"
        )
        val types = requireNotNull(RimeInputTypesRepository.read(dir))
        assertEquals(listOf("removed_pinyin", "zrm"), types.types.map { it.option })
        assertEquals(listOf("zrm"), types.enabled)
        assertEquals("zrm", types.reset)
        assertEquals("自然码", types.summary())
    }

    @Test fun wholeGroupPatchWithoutResetFallsBackToTheFirstEnabledType() {
        val dir = user(
            customText = "patch:\n" +
                "  input_types:\n" +
                "    enabled: [flypy]\n" +
                "    options:\n" +
                "      - {option: pinyin, label: 全拼}\n" +
                "      - {option: flypy, label: 小鹤双拼}\n"
        )
        val types = requireNotNull(RimeInputTypesRepository.read(dir))
        assertEquals(listOf("flypy"), types.enabled)
        assertNull(types.reset)
        assertEquals("小鹤双拼", types.current?.label)
        assertEquals("小鹤双拼", types.summary())
    }

    @Test fun missingEnabledListMeansEveryDeclaredTypeIsUsable() {
        val dir = user(
            customText = "patch:\n" +
                "  input_types:\n" +
                "    options:\n" +
                "      - {option: pinyin, label: 全拼}\n" +
                "      - {option: zrm, label: 自然码}\n"
        )
        val types = requireNotNull(RimeInputTypesRepository.read(dir))
        assertEquals(listOf("pinyin", "zrm"), types.enabled)
        assertNull(types.reset)
        assertEquals("全拼", types.current?.label)
    }

    @Test fun menuUsesOnlyEnabledAndDeployedOptionsInDeclarationOrder() {
        val dir = user(customText = "patch:\n  input_types/enabled: [flypy, zrm, pinyin]\n")
        val state = requireNotNull(RimeInputTypesRepository.read(dir))
        assertEquals(listOf("全拼", "自然码", "小鹤双拼"),
            state.selectableTypes(listOf("flypy", "zrm", "pinyin")).map { it.label })
        assertEquals(listOf("全拼", "自然码"),
            state.selectableTypes(listOf("zrm", "pinyin")).map { it.label })
        assertTrue(state.selectableTypes(emptyList()).isEmpty())

        val restricted = requireNotNull(RimeInputTypesRepository.read(user()))
        assertEquals(listOf("自然码"),
            restricted.selectableTypes(listOf("pinyin", "zrm", "flypy")).map { it.label })
        assertTrue(restricted.selectableTypes(listOf("flypy")).isEmpty())
    }

    @Test fun catalogRemainsCompleteAndReadingNeverWritesCustom() {
        val dir = user()
        val file = File(dir, "wanxiang.custom.yaml")
        val before = file.readBytes()
        val state = requireNotNull(RimeInputTypesRepository.read(dir, "wanxiang"))
        assertEquals(listOf("pinyin", "zrm", "flypy"), state.types.map { it.option })
        assertEquals(listOf("zrm"), state.enabled)
        assertTrue(before.contentEquals(file.readBytes()))
        val unpatched = user(customText = null)
        assertEquals(3, RimeInputTypesRepository.read(unpatched)?.types?.size)
        assertTrue(!File(unpatched, "wanxiang.custom.yaml").exists())
    }
}
