package com.example.blocker

import android.content.Context
import com.example.R
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Fast, immutable, thread-safe domain blocklist engine supporting exact and parent-subdomain
 * matching (e.g., blocking "doubleclick.net" also blocks "ad.doubleclick.net").
 */
class AdBlocklistEngine private constructor(private val blockedDomains: Set<String>) {

  val ruleCount: Int
    get() = blockedDomains.size

  fun isBlocked(rawDomain: String): Boolean {
    val normalized = normalizeDomain(rawDomain) ?: return false
    if (blockedDomains.contains(normalized)) return true

    var dotIndex = normalized.indexOf('.')
    while (dotIndex != -1) {
      val parent = normalized.substring(dotIndex + 1)
      // Do not match top-level domains alone (must contain at least one dot)
      if (!parent.contains('.')) break
      if (blockedDomains.contains(parent)) return true
      dotIndex = normalized.indexOf('.', dotIndex + 1)
    }
    return false
  }

  companion object {
    fun fromContext(context: Context): AdBlocklistEngine {
      return context.resources.openRawResource(R.raw.ad_blocklist).use { stream ->
        fromInputStream(stream)
      }
    }

    fun fromInputStream(inputStream: InputStream): AdBlocklistEngine {
      val domains = HashSet<String>(512)
      BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).useLines { lines ->
        lines.forEach { rawLine ->
          val cleaned = rawLine.substringBefore('#').trim()
          if (cleaned.isNotEmpty()) {
            // Support either plain domain lines or hosts-file style ("0.0.0.0 domain")
            val candidate =
              if (cleaned.contains(' ') || cleaned.contains('\t')) {
                cleaned.split(Regex("\\s+")).lastOrNull().orEmpty()
              } else {
                cleaned
              }
            normalizeDomain(candidate)?.let { domains.add(it) }
          }
        }
      }
      return AdBlocklistEngine(domains)
    }

    fun fromDomains(domains: Collection<String>): AdBlocklistEngine {
      val normalized = domains.mapNotNull { normalizeDomain(it) }.toHashSet()
      return AdBlocklistEngine(normalized)
    }

    internal fun normalizeDomain(domain: String): String? {
      val trimmed = domain.trim().trimEnd('.').lowercase()
      if (trimmed.isEmpty() || !trimmed.contains('.')) return null
      return trimmed
    }
  }
}
