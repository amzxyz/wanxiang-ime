/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.*
import org.junit.Test

class DirectRimeCandidateSequenceTest {
    @Test fun nativeActionsHaveStableIdsAndNames() {
        val actions = DirectRimeCandidateSequence.actions(true)
        assertEquals(listOf(0, 2, 3, 4), actions.map { it.id })
        assertEquals(listOf("重置自动调频", "前移一位", "后移一位", "重置手动排序"), actions.map { it.text })
        assertEquals("auto_delete", DirectRimeCandidateSequence.operation(0)?.action)
        assertEquals("manual_up", DirectRimeCandidateSequence.operation(2)?.action)
        assertEquals("manual_down", DirectRimeCandidateSequence.operation(3)?.action)
        assertEquals("manual_delete", DirectRimeCandidateSequence.operation(4)?.action)
        assertNull(DirectRimeCandidateSequence.operation(1))
    }

    @Test fun olderApiKeepsStandardCandidateDeletion() {
        assertEquals(listOf(0), DirectRimeCandidateSequence.actions(false).map { it.id })
    }
}
