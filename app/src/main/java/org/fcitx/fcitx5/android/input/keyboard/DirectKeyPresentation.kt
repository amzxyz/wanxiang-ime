/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.DirectRimeInputMethodEntryCache
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.editorUnicode

/** Real non-Rime input and password direct input are literal; unknown previews stay Chinese. */
internal fun isDirectKeyboardInput(ime: InputMethodEntry?, password: Boolean, preview: Boolean = false): Boolean =
    password || ime?.subMode?.name == DirectRimeInputMethodEntryCache.LATIN_MODE_NAME ||
        (!preview && ime != null && ime.uniqueName != DirectRimeInputMethodEntryCache.IME_ID)

internal fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

internal fun String.isSinglePunctuationLabel(): Boolean =
    length == 1 && !this[0].isLetterOrDigit() && !this[0].isWhitespace()

/** A literal macro is never implicitly normalized, including single-character macros. */
internal fun KeyAction?.literalMacroText(direct: Boolean): String? = when (this) {
    is KeyAction.CommitAction -> text
    is MacroAction -> (steps.singleOrNull() as? MacroStep.Text)?.textFor(direct)
    else -> null
}

/** Only simple, printable, single-key actions have a reliable direct keycap. */
internal fun KeyAction?.directKeyText(): String? {
    val code = when (this) {
        is KeyAction.FcitxKeyAction -> act.takeIf { !up && states == KeyStates.Virtual }
        is KeyAction.SymAction -> return sym.editorUnicode.takeIf { it in 33..126 }?.toChar()?.toString()
        is MacroAction -> ((steps.singleOrNull() as? MacroStep.Tap)?.keys?.singleOrNull() as? KeyRef.Fcitx)?.code
        else -> null
    } ?: return null
    return MacroKey.keyDisplayText(code).takeIf { it.length == 1 && it[0] in '!'..'~' }
}

/** Only ordinary punctuation labels follow actual bound output; named macros remain named. */
internal fun directActionLabel(original: String, action: KeyAction?, direct: Boolean): String {
    if (!direct || !original.isSinglePunctuationLabel()) return original
    return (action.literalMacroText(true) ?: action.directKeyText())
        ?.takeIf { it.length == 1 } ?: original
}
