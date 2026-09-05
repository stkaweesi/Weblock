package com.sarr.websiteblocker

import android.content.Context

object BlocklistStore {
    private const val PREFS_NAME = "website_blocker_prefs"
    private const val KEY_DOMAINS = "blocked_domains"

    fun getBlockedDomains(context: Context): MutableSet<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return HashSet(prefs.getStringSet(KEY_DOMAINS, emptySet()) ?: emptySet())
    }

    fun saveBlockedDomains(context: Context, domains: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_DOMAINS, domains).apply()
    }

    fun normalize(domain: String): String {
        return domain.trim()
            .lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')
    }

    /** True if [host] matches a blocked domain exactly or is a subdomain of one. */
    fun isBlocked(host: String, blockedDomains: Set<String>): Boolean {
        val h = host.lowercase().trimEnd('.')
        for (domain in blockedDomains) {
            if (h == domain || h.endsWith(".$domain")) return true
        }
        return false
    }
}
