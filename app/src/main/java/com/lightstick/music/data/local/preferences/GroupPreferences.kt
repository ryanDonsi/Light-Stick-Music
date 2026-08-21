package com.lightstick.music.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import com.lightstick.music.core.constants.PrefsKeys
import com.lightstick.types.GroupPalette

/**
 * 이번 행사의 전체 그룹 개수 관리
 * - 주최자가 GroupAssignDialog에서 설정한 그룹 개수(1~20)를 저장
 * - SDK GroupPalette 범위(MIN_GROUP_ID..MAX_GROUP_ID)를 벗어나지 않도록 항상 clamp
 */
object GroupPreferences {

    val DEFAULT_GROUP_COUNT = GroupPalette.MAX_GROUP_ID

    private fun getPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PrefsKeys.PREFS_GROUP, Context.MODE_PRIVATE)

    /** 전체 그룹 개수 조회 (1~20) */
    fun getGroupCount(context: Context): Int =
        getPreferences(context)
            .getInt(PrefsKeys.KEY_GROUP_COUNT, DEFAULT_GROUP_COUNT)
            .coerceIn(GroupPalette.MIN_GROUP_ID, GroupPalette.MAX_GROUP_ID)

    /** 전체 그룹 개수 저장 */
    fun setGroupCount(context: Context, count: Int) {
        getPreferences(context).edit()
            .putInt(PrefsKeys.KEY_GROUP_COUNT, count.coerceIn(GroupPalette.MIN_GROUP_ID, GroupPalette.MAX_GROUP_ID))
            .apply()
    }
}
