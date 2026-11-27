/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

/** Native editor actions, independent of schema names and physical key bindings. */
internal object DirectRimeCandidateSequence {
    enum class Operation(val id: Int, val action: String, val label: String) {
        ResetAuto(0, "auto_delete", "重置自动调频"),
        Forward(2, "manual_up", "前移一位"),
        Backward(3, "manual_down", "后移一位"),
        ResetManual(4, "manual_delete", "重置手动排序"),
    }

    fun operation(id: Int): Operation? = Operation.entries.firstOrNull { it.id == id }

    fun actions(nativeEditing: Boolean): List<CandidateAction> =
        Operation.entries.filter { nativeEditing || it == Operation.ResetAuto }
            .map { CandidateAction(it.id, it.label, false, "", false, false) }
}
