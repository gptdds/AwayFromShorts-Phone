package com.afs.guard

import com.afs.feature.phase2.TimelineDraft

object TimelineRuleConversion {
    fun canonicalFreeze(draft: TimelineDraft, freezeMode: Boolean): Rule? {
        if (freezeMode) return Rule(draft.daysMask, draft.toRuleWindows(), true, true)
        return Rule(draft.daysMask, draft.toRuleWindows(), true, false).freezeEditorRule()
    }

    fun memberRule(
        current: Rule,
        draft: TimelineDraft,
        originalFreeze: Boolean,
        canonical: Rule?
    ): Rule {
        if (current.freeze == originalFreeze) {
            return Rule(draft.daysMask, draft.toRuleWindows(), current.gate, current.freeze)
        }
        if (canonical == null) {
            throw IllegalStateException("同组应用保存格式不一致，请在冻结页编辑这个时段")
        }
        if (current.freeze) {
            return Rule(canonical.days, canonical.windows(), current.gate, true)
        }
        val allowed = allowedWindows(canonical)
            ?: throw IllegalStateException("同组含旧版周规则，无法安全换算，请在冻结页编辑")
        return Rule(Rule.ALL_DAYS, allowed, current.gate, false)
    }

    fun allowedWindows(freeze: Rule): Array<IntArray>? {
        if (freeze.days != Rule.ALL_DAYS) return null
        val blocked = BooleanArray(1440)
        for (window in freeze.windows()) {
            if (window[0] == window[1]) {
                blocked.fill(true)
            } else if (window[0] < window[1]) {
                for (minute in window[0] until window[1]) blocked[minute] = true
            } else {
                for (minute in window[0] until 1440) blocked[minute] = true
                for (minute in 0 until window[1]) blocked[minute] = true
            }
        }
        if (blocked.all { it }) return emptyArray()
        if (blocked.none { it }) return arrayOf(intArrayOf(0, 0))
        val result = ArrayList<IntArray>()
        var minute = 0
        while (minute < 1440) {
            if (blocked[minute]) {
                minute++
                continue
            }
            val start = minute
            while (minute < 1440 && !blocked[minute]) minute++
            result.add(intArrayOf(start, minute % 1440))
        }
        return result.toTypedArray()
    }
}

fun TimelineDraft.toRuleWindows(): Array<IntArray> =
    windows.map { intArrayOf(it.start, it.end) }.toTypedArray()
