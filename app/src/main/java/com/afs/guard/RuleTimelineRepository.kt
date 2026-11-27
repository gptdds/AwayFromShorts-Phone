package com.afs.guard

import com.afs.feature.phase2.TimelineDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import java.util.UUID

data class TimelineSaveResult(
    val members: Int,
    val schedule: String
)

class RuleTimelineRepository(context: android.content.Context) {
    private val app = context.applicationContext

    suspend fun save(packageName: String, draft: TimelineDraft): TimelineSaveResult =
        withContext(Dispatchers.IO) {
            if (!(TimeWindow.on(app) || Config.inGrace(app))) {
                throw IllegalStateException("修改冻结时段要等调整时段 ${TimeWindow.label(app)}")
            }
            if (draft.overlaps().isNotEmpty()) {
                throw IllegalStateException("时段存在重叠，请先合并或调整")
            }
            val rules = RuleStore.all(app)
            val original = rules[packageName]
                ?: throw IllegalStateException("应用已不在当前规则中")
            val slots = FreezeSlotStore.all(app)
            val oldSlot = slots.firstOrNull {
                FreezeSlotStore.scheduleKey(it.rule) == FreezeSlotStore.scheduleKey(original)
            }
            val members = if (oldSlot == null) listOf(packageName)
            else FreezeSlotStore.members(oldSlot, rules).ifEmpty { listOf(packageName) }
            val canonical = TimelineRuleConversion.canonicalFreeze(draft, original.freeze)
            val slotRule = if (canonical == null) {
                Rule(draft.daysMask, draft.toRuleWindows(), true, original.freeze)
            } else {
                Rule(canonical.days, canonical.windows(), true, true)
            }
            val nextKey = FreezeSlotStore.scheduleKey(slotRule)

            if (oldSlot != null) {
                if (slots.any { it.id != oldSlot.id && FreezeSlotStore.scheduleKey(it.rule) == nextKey }) {
                    throw IllegalStateException("已有相同冻结时段，请从冻结页加入该时段")
                }
                val updated = LinkedHashMap<String, Rule>()
                for (member in members) {
                    val current = rules[member] ?: continue
                    updated[member] = TimelineRuleConversion.memberRule(
                        current = current,
                        draft = draft,
                        originalFreeze = original.freeze,
                        canonical = canonical
                    )
                }
                for ((member, rule) in updated) RuleStore.put(app, member, rule)
                oldSlot.rule = slotRule
                FreezeSlotStore.save(app, slots)
                Guard.applyAsync(app)
                TimelineSaveResult(members.size, scheduleText(slotRule))
            } else {
                val target = slots.firstOrNull { FreezeSlotStore.scheduleKey(it.rule) == nextKey }
                if (target != null) {
                    val targetMembers = FreezeSlotStore.members(target, rules)
                    RuleStore.put(app, packageName, target.rule.withGate(original.gate))
                    Guard.applyAsync(app)
                    TimelineSaveResult(targetMembers.size + 1, scheduleText(target.rule))
                } else {
                    RuleStore.put(app, packageName, slotRule.withGate(original.gate))
                    slots.add(FreezeSlotStore.Slot(UUID.randomUUID().toString(), slotRule))
                    FreezeSlotStore.save(app, slots)
                    Guard.applyAsync(app)
                    TimelineSaveResult(1, scheduleText(slotRule))
                }
            }
        }

    private fun scheduleText(rule: Rule): String {
        val days = Rule.daysText(rule.days)
        val mode = if (rule.freeze) "冻结" else "放行"
        val windows = rule.windows().joinToString("、") { window ->
            "${hm(window[0])}–${hm(if (window[1] == 0 && window[0] > 0) 1440 else window[1])}"
        }.ifEmpty { "无时段" }
        return "$days · $windows $mode"
    }

    private fun hm(minute: Int): String =
        if (minute >= 1440) "24:00" else TimeWindow.clock(minute)
}
