package com.example.k2ctranslator.auth

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.json.JSONArray
import org.json.JSONObject

data class AuthResult(val ok: Boolean, val message: String)

object AuthStore {
    private const val PREF = "auth_store"
    private const val KEY_USERS = "users_json"
    private const val KEY_SESSION = "session_user"
    private const val ITERATIONS = 120_000
    private const val KEY_LEN_BITS = 256

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun currentUser(context: Context): String? {
        return prefs(context).getString(KEY_SESSION, null)
    }

    fun logout(context: Context) {
        prefs(context).edit().remove(KEY_SESSION).apply()
    }

    fun register(context: Context, usernameRaw: String, passwordRaw: String): AuthResult {
        val username = usernameRaw.trim()
        if (username.length < 3) return AuthResult(false, "用户名至少 3 位")
        if (passwordRaw.length < 6) return AuthResult(false, "密码至少 6 位")

        val data = loadUsers(context)
        if (data.has(username)) return AuthResult(false, "用户名已存在")

        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val hash = pbkdf2(passwordRaw, salt)

        val u = JSONObject()
        u.put("salt", b64(salt))
        u.put("hash", b64(hash))
        u.put("iter", ITERATIONS)

        data.put(username, u)
        saveUsers(context, data)
        prefs(context).edit().putString(KEY_SESSION, username).apply()
        return AuthResult(true, "注册成功")
    }

    fun login(context: Context, usernameRaw: String, passwordRaw: String): AuthResult {
        val username = usernameRaw.trim()
        if (username.isEmpty()) return AuthResult(false, "请输入用户名")
        if (passwordRaw.isEmpty()) return AuthResult(false, "请输入密码")

        val data = loadUsers(context)
        val u = data.optJSONObject(username) ?: return AuthResult(false, "用户名或密码错误")
        val salt = b64d(u.optString("salt", ""))
        val hash = b64d(u.optString("hash", ""))
        val iter = u.optInt("iter", ITERATIONS)
        if (salt.isEmpty() || hash.isEmpty()) return AuthResult(false, "用户名或密码错误")

        val computed = pbkdf2(passwordRaw, salt, iter)
        if (!constantTimeEquals(hash, computed)) return AuthResult(false, "用户名或密码错误")
        prefs(context).edit().putString(KEY_SESSION, username).apply()
        return AuthResult(true, "登录成功")
    }

    fun listUsers(context: Context): List<String> {
        val data = loadUsers(context)
        val keys = data.keys()
        val out = ArrayList<String>()
        while (keys.hasNext()) out.add(keys.next())
        out.sort()
        return out
    }

    private fun loadUsers(context: Context): JSONObject {
        val raw = prefs(context).getString(KEY_USERS, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Throwable) {
            JSONObject()
        }
    }

    private fun saveUsers(context: Context, obj: JSONObject) {
        prefs(context).edit().putString(KEY_USERS, obj.toString()).apply()
    }

    private fun pbkdf2(password: String, salt: ByteArray, iter: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iter, KEY_LEN_BITS)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return skf.generateSecret(spec).encoded
    }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun b64d(s: String): ByteArray = try { Base64.decode(s, Base64.NO_WRAP) } catch (_: Throwable) { byteArrayOf() }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt())
        return r == 0
    }
}

