package com.worktimer.pomo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Project(val id: String, val name: String, val color: Int)

/** Проекты: один по умолчанию («Основной»), остальные — свои. */
object Projects {
    const val MAIN = "main"

    val PALETTE = intArrayOf(
        0xFF1A88B0.toInt(), // синий
        0xFF1E9E92.toInt(), // бирюзовый
        0xFF5B7FD6.toInt(), // васильковый
        0xFFE08A3C.toInt(), // оранжевый
        0xFF9B6BC9.toInt(), // сиреневый
        0xFF6A9F3A.toInt(), // зелёный
        0xFFD0567A.toInt(), // малиновый
        0xFF7A8C99.toInt(), // серый
    )

    private fun p(c: Context) = c.getSharedPreferences("projects", Context.MODE_PRIVATE)

    fun all(c: Context): List<Project> {
        val list = mutableListOf<Project>()
        val raw = p(c).getString("list", null)
        if (raw != null) runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += Project(o.getString("id"), o.getString("name"), o.optInt("color", i))
            }
        }
        if (list.none { it.id == MAIN }) list.add(0, Project(MAIN, "Основной", 0))
        return list
    }

    private fun saveAll(c: Context, list: List<Project>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)) }
        p(c).edit().putString("list", arr.toString()).commit()
    }

    fun currentId(c: Context): String {
        val id = p(c).getString("current", MAIN) ?: MAIN
        return if (all(c).any { it.id == id }) id else MAIN
    }

    fun current(c: Context): Project = all(c).first { it.id == currentId(c) }

    fun setCurrent(c: Context, id: String) {
        p(c).edit().putString("current", id).commit()
    }

    fun add(c: Context, name: String): Project {
        val list = all(c).toMutableList()
        val used = list.map { it.color }.toSet()
        val color = PALETTE.indices.firstOrNull { it !in used } ?: (list.size % PALETTE.size)
        val pr = Project("p" + System.currentTimeMillis(), name, color)
        list += pr
        saveAll(c, list)
        return pr
    }

    fun rename(c: Context, id: String, name: String) {
        saveAll(c, all(c).map { if (it.id == id) it.copy(name = name) else it })
    }

    /** Удаляет проект из списка. Его время остаётся в общей статистике. */
    fun delete(c: Context, id: String) {
        if (id == MAIN) return
        saveAll(c, all(c).filter { it.id != id })
    }

    fun colorOf(pr: Project): Int = PALETTE[pr.color.mod(PALETTE.size)]

    /** Цвет для времени удалённых проектов. */
    const val DELETED_COLOR = 0xFFA9B4B9.toInt()
}
