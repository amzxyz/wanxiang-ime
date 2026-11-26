/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core.engine

import org.fcitx.fcitx5.android.data.WanxiangAdvancedFiles
import org.fcitx.fcitx5.android.data.WanxiangYaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import java.io.File

/** One selectable input type declared by the schema's `input_types/options`. */
internal data class RimeInputType(val option: String, val label: String)

/**
 * Input types declared by the active scheme, plus the patch that currently overrides them.
 *
 * The list is never stored by the app: it is read from the loaded scheme each time so that a
 * scheme update (or a different working directory) is reflected without migration.
 */
internal data class RimeInputTypes(
    val types: List<RimeInputType>,
    val enabled: List<String>,
    val reset: String?,
    val schemaId: String,
    val customFile: File
) {
    val current: RimeInputType?
        get() = types.firstOrNull { it.option == reset }
            ?: enabled.firstNotNullOfOrNull { option -> types.firstOrNull { it.option == option } }

    fun summary(): String = current?.label ?: enabled.joinToString("、")

    /** Labels and order come from options; both effective enabled and deployed types constrain the menu. */
    fun selectableTypes(deployed: Collection<String>): List<RimeInputType> =
        types.filter { it.option in enabled && it.option in deployed }
}

/** Read-only source catalog; runtime choices never edit deployment configuration. */
internal object RimeInputTypesRepository {
    private const val SCHEMA_SUFFIX = ".schema.yaml"
    private const val CUSTOM_SUFFIX = ".custom.yaml"
    private val SAFE_SCHEMA_ID = Regex("[A-Za-z0-9._-]+")

    fun read(user: File, activeSchema: String? = null): RimeInputTypes? {
        if (!user.isDirectory) return null
        val yaml = WanxiangYaml()
        val schema = findSchema(user, activeSchema, yaml) ?: return null
        val group = effectiveGroup(yaml, schema.group, schema.customFile) ?: return null
        val types = parseTypes(yaml, yaml.member(group, "options") as? SequenceNode)
        if (types.isEmpty()) return null
        val enabledNode = yaml.member(group, "enabled")
        // librime treats a missing enabled list as "all declared options"; only an explicit
        // empty list means nothing can be activated.
        var enabled = if (enabledNode == null) types.map { it.option }
        else stringList(enabledNode).orEmpty()
        var reset = (yaml.member(group, "reset") as? ScalarNode)?.value?.trim()
            ?.takeIf { it.isNotEmpty() }
        val known = types.map { it.option }.toSet()
        enabled = enabled.filter { it in known }.distinct()
        // InputTypesConfig::Load rejects a reset that is not enabled, so it is not active.
        if (reset !in known || reset !in enabled) reset = null
        return RimeInputTypes(types, enabled, reset, schema.id, schema.customFile)
    }

    private data class SchemaTypes(
        val id: String,
        val group: MappingNode,
        val customFile: File
    )

    private fun findSchema(user: File, activeSchema: String?, yaml: WanxiangYaml): SchemaTypes? {
        val active = activeSchema?.trim().orEmpty()
        val files = if (active.isNotEmpty()) {
            if (!SAFE_SCHEMA_ID.matches(active)) return null
            listOf(File(user, active + SCHEMA_SUFFIX))
        } else {
            user.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(SCHEMA_SUFFIX) }
                .sortedBy { it.name }
        }
        for (file in files.distinctBy { it.absolutePath }) {
            if (!file.isFile) continue
            val schemaId = file.name.removeSuffix(SCHEMA_SUFFIX)
            if (!SAFE_SCHEMA_ID.matches(schemaId)) continue
            val root = runCatching { yaml.document(WanxiangAdvancedFiles.read(file)) }.getOrNull()
                ?: continue
            val group = yaml.get(root, "input_types") as? MappingNode ?: continue
            if (parseTypes(yaml, yaml.member(group, "options") as? SequenceNode).isEmpty()) continue
            return SchemaTypes(schemaId, group, File(user, schemaId + CUSTOM_SUFFIX))
        }
        return null
    }

    /**
     * Applies the custom patch with Rime's compiler semantics: patch keys run in ConfigMap
     * (lexicographic) order, a whole-node patch replaces the group instead of merging it, and
     * only /+ merges a map or appends a list. [WanxiangYaml.literalView] already implements
     * those rules, so reuse it over a wrapper holding just the input_types group.
     */
    private fun effectiveGroup(yaml: WanxiangYaml, source: MappingNode, customFile: File): MappingNode? {
        if (!customFile.isFile) return source
        val custom = runCatching { patch(yaml, customFile) }.getOrNull() ?: return null
        val base = yaml.mapping()
        yaml.put(base, "input_types", yaml.clone(source))
        val patched = yaml.mapping()
        yaml.put(patched, "patch", yaml.clone(custom))
        return yaml.member(yaml.literalView(base, patched), "input_types") as? MappingNode
    }

    private fun parseTypes(yaml: WanxiangYaml, sequence: SequenceNode?): List<RimeInputType> =
        sequence?.value.orEmpty().mapNotNull { node ->
            val entry = node as? MappingNode ?: return@mapNotNull null
            val option = (yaml.member(entry, "option") as? ScalarNode)?.value?.trim().orEmpty()
            if (option.isEmpty()) return@mapNotNull null
            val label = (yaml.member(entry, "label") as? ScalarNode)?.value?.trim()
                ?.takeIf { it.isNotEmpty() } ?: option
            RimeInputType(option, label)
        }.distinctBy { it.option }

    private fun patch(yaml: WanxiangYaml, file: File): MappingNode =
        patch(yaml, WanxiangAdvancedFiles.read(file))

    private fun patch(yaml: WanxiangYaml, text: String): MappingNode {
        val root = yaml.document(text)
        return yaml.member(root, "patch") as? MappingNode ?: error("补丁文件缺少 patch 节点")
    }

    private fun stringList(node: Node?): List<String>? = when (node) {
        null -> null
        is ScalarNode -> node.value.trim().takeIf { it.isNotEmpty() }?.let(::listOf)
        is SequenceNode -> node.value.mapNotNull {
            (it as? ScalarNode)?.value?.trim()?.takeIf(String::isNotEmpty)
        }
        else -> null
    }

}
