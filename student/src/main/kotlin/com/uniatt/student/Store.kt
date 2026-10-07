package com.uniatt.student

import android.content.Context
import com.uniatt.core.Hex
import com.uniatt.core.StudentProfile
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(val time: Long, val sessionId: String, val status: Int)

/** مفتاح الطالب المشتق من كوده (الكود نفسه لا يُحفظ). */
class Credentials(val tag: String, val key: ByteArray)

/** آخر اتصال وصل من هاتف دكتور (للتشخيص): الوقت والخطوة. */
data class Contact(val time: Long, val step: String)

object ResultBus {
    val contact = MutableStateFlow<Contact?>(null)
    val last = MutableStateFlow<HistoryItem?>(null)
    val profileVersion = MutableStateFlow(0)      // يزداد عند وصول بيانات الطالب من هاتف دكتور
}

class StudentStore(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("student", Context.MODE_PRIVATE)

    // ---- الكود (يُدخل مرة واحدة) ----
    fun credentials(): Credentials? {
        val tag = sp.getString("tag", null) ?: return null
        val key = sp.getString("key", null) ?: return null
        return Credentials(tag, Hex.dec(key))
    }

    fun saveCredentials(tag: String, key: ByteArray) {
        sp.edit().putString("tag", tag).putString("key", Hex.enc(key)).apply()
    }

    // ---- البيانات (تصل عند أول تقريب ناجح، وتُدمج مواد كل دكتور) ----
    fun profile(): StudentProfile? {
        val id = sp.getString("id", null) ?: return null
        fun g(k: String) = sp.getString(k, "") ?: ""
        val a = JSONArray(sp.getString("courses", "[]"))
        return StudentProfile(id, g("name"), g("faculty"), g("major"), g("level"), g("section"),
            (0 until a.length()).map { a.getString(it) })
    }

    @Synchronized
    fun mergeProfile(p: StudentProfile) {
        val old = JSONArray(sp.getString("courses", "[]"))
        val all = LinkedHashSet<String>()
        for (i in 0 until old.length()) all.add(old.getString(i))
        all.addAll(p.courses)
        sp.edit().putString("id", p.studentId).putString("name", p.name).putString("faculty", p.faculty)
            .putString("major", p.major).putString("level", p.level).putString("section", p.section)
            .putString("courses", JSONArray(all.toList()).toString()).apply()
        ResultBus.profileVersion.value++
    }

    // ---- السجل ----
    fun history(): List<HistoryItem> {
        val a = JSONArray(sp.getString("hist", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            HistoryItem(o.getLong("t"), o.getString("s"), o.getInt("r"))
        }.reversed()
    }

    @Synchronized
    fun addHistory(item: HistoryItem) {
        val a = JSONArray(sp.getString("hist", "[]"))
        a.put(JSONObject().put("t", item.time).put("s", item.sessionId).put("r", item.status))
        sp.edit().putString("hist", a.toString()).apply()
        ResultBus.last.value = item
    }

    /** يمسح الكود والبيانات والسجل (مفتاح Keystore يبقى). */
    fun clearAll() { sp.edit().clear().apply(); ResultBus.last.value = null; ResultBus.profileVersion.value++ }
}
