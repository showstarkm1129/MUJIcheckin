package io.github.mujicheckin

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class Store(val id: String, val name: String, val lat: Double, val lng: Double)

/** 設定・学習済み店舗・チェックイン履歴の保存先。 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("muji_checkin", Context.MODE_PRIVATE)

    /** 自分でMUJIアプリを開いたときもボタンを自動タップする */
    var assistManualOpen: Boolean
        get() = sp.getBoolean("assist_manual", true)
        set(v) = sp.edit().putBoolean("assist_manual", v).apply()

    /** 店舗に近づいたらMUJIアプリを自動で開いてチェックインする */
    var autoLaunch: Boolean
        get() = sp.getBoolean("auto_launch", true)
        set(v) = sp.edit().putBoolean("auto_launch", v).apply()

    /** 自動で開かず、「チェックインしますか？」通知のボタンを押してから実行する */
    var confirmByNotification: Boolean
        get() = sp.getBoolean("confirm_notification", false)
        set(v) = sp.edit().putBoolean("confirm_notification", v).apply()

    /** ジオフェンス進入後、画面ロック解除を待っている状態の開始時刻（0なら待機なし） */
    var pendingSince: Long
        get() = sp.getLong("pending_since", 0L)
        set(v) = sp.edit().putLong("pending_since", v).apply()

    /** ロック解除後にチェックインしたい店舗ID */
    var pendingStoreIds: Set<String>
        get() = sp.getStringSet("pending_ids", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("pending_ids", v).apply()

    /** 店舗名 → 最後にチェックインした日付（yyyy-MM-dd） */
    private val checkinDates: JSONObject
        get() = JSONObject(sp.getString("checkin_dates", "{}")!!)

    fun isCheckedInToday(storeName: String): Boolean =
        checkinDates.optString(storeName) == LocalDate.now().toString()

    fun storesCheckedInToday(): List<String> {
        val dates = checkinDates
        val today = LocalDate.now().toString()
        return dates.keys().asSequence().filter { dates.getString(it) == today }.toList()
    }

    fun recordCheckin(storeName: String) {
        val dates = checkinDates
        val today = LocalDate.now().toString()
        // 古い日付は不要なので掃除しておく
        dates.keys().asSequence().toList().filter { dates.getString(it) != today }.forEach { dates.remove(it) }
        dates.put(storeName, today)
        sp.edit().putString("checkin_dates", dates.toString()).apply()
    }

    /** 指定IDのうち、今日まだチェックインしていない登録店舗 */
    fun uncheckedStores(ids: Collection<String>): List<Store> =
        stores.filter { it.id in ids && !isCheckedInToday(it.name) }

    var stores: List<Store>
        get() {
            val arr = JSONArray(sp.getString("stores", "[]"))
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Store(o.getString("id"), o.getString("name"), o.getDouble("lat"), o.getDouble("lng"))
            }
        }
        set(list) {
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().put("id", it.id).put("name", it.name).put("lat", it.lat).put("lng", it.lng))
            }
            sp.edit().putString("stores", arr.toString()).apply()
        }

    /**
     * 同名の店舗があれば位置を更新し、なければ追加する。追加/更新したら true。
     * 隣接する別店舗（例: 横浜ジョイナスとＮＥＷｏＭａｎ）を潰さないよう、距離では同一視しない。
     */
    fun upsertStore(name: String, lat: Double, lng: Double): Boolean {
        val list = stores.toMutableList()
        val idx = list.indexOfFirst { it.name == name }
        if (idx >= 0) {
            val old = list[idx]
            if (distance(old.lat, old.lng, lat, lng) < 30f) return false
            list[idx] = old.copy(lat = lat, lng = lng)
        } else {
            list += Store("store_${System.currentTimeMillis()}", name, lat, lng)
        }
        stores = list
        return true
    }

    fun removeStore(id: String) {
        stores = stores.filterNot { it.id == id }
    }

    private fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, out)
        return out[0]
    }
}
