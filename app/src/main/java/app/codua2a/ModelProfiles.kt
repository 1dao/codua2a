package app.codua2a

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Multiple encrypted profiles, with a flat active projection for legacy callers. */
object ModelProfiles {
    private val fields = listOf("base_url", "model", "api_key", "auth_style")
    fun normalize(source: JSONObject): JSONObject {
        val result = JSONObject(source.toString())
        if (!result.has("profiles")) {
            val profiles = JSONArray()
            if (result.optString("model").isNotBlank()) {
                val profile = JSONObject().put("id", "legacy")
                fields.forEach { key -> profile.put(key, result.optString(key)) }
                profiles.put(profile)
            }
            result.put("profiles", profiles)
        }
        val profiles = list(result)
        val active = profiles.find { it.getString("id") == result.optString("active_profile") } ?: profiles.firstOrNull()
        fields.forEach { result.remove(it) }
        if (active != null) {
            result.put("active_profile", active.getString("id"))
            fields.forEach { key -> result.put(key, active.optString(key)) }
        } else result.remove("active_profile")
        return result
    }
    fun list(config: JSONObject): List<JSONObject> {
        val items = config.optJSONArray("profiles") ?: return emptyList()
        return (0 until items.length()).map { items.getJSONObject(it) }
    }
    fun selected(config: JSONObject): JSONObject? = list(config).find { it.optString("id") == config.optString("active_profile") }
    fun select(config: JSONObject, id: String): JSONObject {
        check(list(config).any { it.getString("id") == id }) { "模型配置不存在" }
        return normalize(JSONObject(config.toString()).put("active_profile", id))
    }
    fun upsert(config: JSONObject, id: String?, profile: JSONObject): JSONObject {
        val result = normalize(config)
        val items = list(result).toMutableList()
        val saved = JSONObject(profile.toString()).put("id", id ?: UUID.randomUUID().toString())
        val index = items.indexOfFirst { it.getString("id") == saved.getString("id") }
        if (index >= 0) items[index] = saved else items.add(saved)
        result.put("profiles", JSONArray(items)).put("active_profile", saved.getString("id"))
        return normalize(result)
    }
}
