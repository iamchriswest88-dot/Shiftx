package com.example.shift.data

import android.content.Context
import android.util.Log
import com.example.shift.api.FirebaseApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class CloudSyncManager(private val context: Context) {
    private val settingsManager = SettingsManager(context)
    private val courseManager = CourseManager(context)
    private val matchManager = MatchCacheManager(context)

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    // Serializes concurrent fullSync calls (startup sync racing a save/delete
    // sync) so two merges can't interleave their read-merge-write cycles.
    private val syncMutex = Mutex()

    private suspend fun getApi(): FirebaseApi? {
        val url = settingsManager.firebaseUrlFlow.first()
        if (url.isNullOrBlank()) return null

        var baseUrl = url
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/"
        }

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        return retrofit.create(FirebaseApi::class.java)
    }

    /**
     * The one sync path: pull both nodes, merge newest-wins with tombstones
     * (see [SyncMerge]), save the merged truth locally, then push it back so
     * the cloud is never behind the device that just synced.
     *
     * Replaces the old split push/pull, whose blind push-on-launch let a stale
     * device overwrite the cloud and resurrect deleted segments.
     *
     * If the pull fails nothing is pushed — a device that can't see the cloud's
     * current state has no business overwriting it.
     */
    suspend fun fullSync() = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val api = getApi() ?: return@withLock
                Log.d("CloudSync", "Full sync: pulling...")
                val cloudCourses = api.pullCourses() ?: emptyList()
                val cloudMatches = api.pullMatches() ?: emptyList()
                val now = System.currentTimeMillis()

                val mergedCourses = SyncMerge.mergeCourses(courseManager.rawCourses(), cloudCourses, now)
                courseManager.saveAllCourses(mergedCourses)

                val liveCourses = mergedCourses.filter { !it.deleted }
                val liveCourseIds = liveCourses.map { it.id }.toSet()
                val mergedMatches = SyncMerge.mergeMatches(
                    matchManager.getAllMatchesRaw(), cloudMatches, liveCourseIds, now
                )
                matchManager.saveAllMatches(mergedMatches)
                matchManager.repairStrayMatches(liveCourses)

                api.pushCourses(mergedCourses)
                // Re-read: repairStrayMatches may have re-homed entries.
                api.pushMatches(matchManager.getAllMatchesRaw())

                // The settings node used to hold API keys in this world-readable
                // DB. Keys now stay on-device; scrub any that are still there.
                try {
                    api.deleteSettings()
                } catch (e: Exception) {
                    Log.w("CloudSync", "Could not scrub legacy settings node", e)
                }

                // Carry the fanfare melody across: edited on the phone, played by
                // the Karoo. Newest edit wins, blank counts as an edit too.
                try {
                    val cloudFanfare = api.pullFanfare() ?: com.example.shift.api.CloudFanfare()
                    val localTs = settingsManager.endRideFanfareUpdatedAtFlow.first()
                    if (cloudFanfare.updatedAt > localTs) {
                        settingsManager.saveEndRideFanfareFromSync(cloudFanfare.pattern, cloudFanfare.updatedAt)
                    } else if (localTs > cloudFanfare.updatedAt) {
                        val localPattern = settingsManager.endRideFanfareFlow.first()
                        api.pushFanfare(com.example.shift.api.CloudFanfare(localPattern, localTs))
                    }
                } catch (e: Exception) {
                    Log.w("CloudSync", "Fanfare sync failed", e)
                }

                // Park this device's crash log where a browser can read it —
                // the Karoo has no other practical way to hand a stack trace over.
                try {
                    val crashLines = CrashLogger.getEntries(context).filter { it.isNotBlank() }
                    if (crashLines.isNotEmpty()) {
                        val device = android.os.Build.MODEL
                            .replace(Regex("[^A-Za-z0-9_-]"), "_")
                            .ifBlank { "unknown" }
                        api.pushCrashLog(device, crashLines.takeLast(400))
                    }
                } catch (e: Exception) {
                    Log.w("CloudSync", "Could not upload crash log", e)
                }

                Log.d("CloudSync", "Full sync complete: ${liveCourseIds.size} segments, ${mergedMatches.size} matches")
            } catch (e: Exception) {
                Log.e("CloudSync", "Full sync failed", e)
            }
        }
    }
}
