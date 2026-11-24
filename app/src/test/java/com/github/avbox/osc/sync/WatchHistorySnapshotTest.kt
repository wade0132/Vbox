package com.github.VVbox.osc.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM 测试：合并逻辑不依赖 Android，可直接放进 app/src/test。 */
class WatchHistorySnapshotTest {

    private val now = 2_000_000L
    private val retention = 90L * 24 * 60 * 60 * 1000

    private fun entry(record: Long = 0L, progress: Long = 0L, percent: Int = -1, deleted: Boolean = false, deletedAt: Long = 0L): SyncEntry {
        val item = SyncEntry()
        item.sourceKey = "site"
        item.vodId = "vod"
        item.recordAt = record
        item.progressAt = progress
        item.percent = percent
        item.durationMs = 100_000L
        item.deleted = deleted
        item.deletedAt = deletedAt
        return item
    }

    @Test
    fun newerProgressWinsWithoutTouchingHistory() {
        val local = SyncSnapshot()
        local.entries["site|vod"] = entry(record = 1_000L, progress = 1_100L, percent = 10)
        val remote = SyncSnapshot()
        remote.entries["site|vod"] = entry(record = 900L, progress = 1_500L, percent = 75)

        val merged = WatchHistorySnapshot.merge(local, remote, now, retention)["site|vod"].required()
        assertEquals(1_000L, merged.recordAt)
        assertEquals(75, merged.percent)
        assertEquals(1_500L, merged.progressAt)
    }

    @Test
    fun newerHistoryWinsWithoutRollingBackProgress() {
        val local = SyncSnapshot()
        local.entries["site|vod"] = entry(record = 1_000L, progress = 1_400L, percent = 40)
        val remote = SyncSnapshot()
        remote.entries["site|vod"] = entry(record = 1_300L, progress = 900L, percent = 90)

        val merged = WatchHistorySnapshot.merge(local, remote, now, retention)["site|vod"].required()
        assertEquals(1_300L, merged.recordAt)
        assertEquals(40, merged.percent)
    }

    @Test
    fun staleDeletionCannotEraseNewerPlayback() {
        val local = SyncSnapshot()
        local.entries["site|vod"] = entry(record = 1_000L, progress = 1_600L, percent = 60)
        val remote = SyncSnapshot()
        remote.entries["site|vod"] = entry(deleted = true, deletedAt = 1_200L)

        val merged = WatchHistorySnapshot.merge(local, remote, now, retention)["site|vod"].required()
        assertFalse(merged.deleted)
        assertEquals(60, merged.percent)
    }

    @Test
    fun newerDeletionWinsAndOldTombstonesArePruned() {
        val local = SyncSnapshot()
        local.entries["site|vod"] = entry(record = 1_000L, progress = 1_100L, percent = 20)
        val remote = SyncSnapshot()
        remote.entries["site|vod"] = entry(deleted = true, deletedAt = 1_800L)
        remote.entries["site|old"] = entry(deleted = true, deletedAt = now - retention - 1L)

        val merged = WatchHistorySnapshot.merge(local, remote, now, retention)
        assertTrue(merged.entries["site|vod"].required().deleted)
        assertFalse(merged.entries.containsKey("site|old"))
    }

    @Test
    fun episodeProgressMergesAndMirrorsTitlePercent() {
        val local = SyncSnapshot()
        val localEp = SyncProgress().apply {
            key = "site" + "vod" + "flag" + "0" + "第1集"
            owner = "site|vod"
            sourceKey = "site"
            vodId = "vod"
            positionMs = 1_000L
            durationMs = 10_000L
            percent = 10
            at = 1_100L
        }
        local.progress[localEp.key] = localEp
        local.entries["site|vod"] = entry(record = 1_000L, progress = 1_100L, percent = 10)

        val remote = SyncSnapshot()
        val remoteEp = localEp.copy().apply { positionMs = 8_000L; percent = 80; at = 1_500L }
        remote.progress[remoteEp.key] = remoteEp
        remote.entries["site|vod"] = entry(record = 900L, progress = 1_500L, percent = 80)

        val merged = WatchHistorySnapshot.merge(local, remote, now, retention)
        assertEquals(8_000L, merged.progress[remoteEp.key].required().positionMs)
        assertEquals(80, merged.entries["site|vod"].required().percent)
        assertEquals(1_500L, merged.entries["site|vod"].required().progressAt)
        val parsed = SyncSnapshot.fromJson(SyncSnapshot.toJson(merged)).required()
        assertEquals(8_000L, parsed.progress[remoteEp.key].required().positionMs)
    }

    @Test
    fun emptyLocalProgressCannotBlockRemoteResumePosition() {
        val local = SyncSnapshot()
        val zero = SyncProgress().apply {
            key = "k"
            owner = "site|vod"
            percent = 0
            at = 1_900L
        }
        local.progress["k"] = zero
        val remote = SyncSnapshot()
        val real = SyncProgress().apply {
            key = "k"
            owner = "site|vod"
            positionMs = 6_000L
            durationMs = 10_000L
            percent = 60
            at = 1_200L
        }
        remote.progress["k"] = real

        val merged = WatchHistorySnapshot.merge(local, remote, now, retention)
        assertEquals(6_000L, merged.progress["k"].required().positionMs)
    }

    @Test
    fun emptyRemoteKeepsLocalAndSerializationRoundTrips() {
        val local = SyncSnapshot()
        local.deviceId = "device-a"
        local.cid = "config-a"
        local.entries["site|vod"] = entry(record = 1_000L, progress = 1_100L, percent = 33)
        val merged = WatchHistorySnapshot.merge(local, null, now, retention)
        val parsed = SyncSnapshot.fromJson(SyncSnapshot.toJson(merged)).required()
        assertEquals("device-a", parsed.deviceId)
        assertEquals(33, parsed.entries["site|vod"].required().percent)
    }

    @Test
    fun historyRecordCountIgnoresProgressOnlyEntriesAndTombstones() {
        val snapshot = SyncSnapshot()
        snapshot.entries["site|1"] = entry(record = 1_000L).apply { dataJson = "{}" }
        snapshot.entries["site|2"] = entry(record = 1_100L).apply { dataJson = "{}" }
        // 只有进度、没有历史元数据的条目：用于续播，不能算作观影记录。
        snapshot.entries["site|3"] = entry(progress = 1_200L, percent = 60)
        // 删除墓碑也不能算记录。
        snapshot.entries["site|4"] = entry(deleted = true, deletedAt = 1_300L)

        assertEquals(2, snapshot.historyRecordCount())
    }

    private operator fun SyncSnapshot.get(key: String): SyncEntry? = entries[key]

    private fun SyncEntry?.required(): SyncEntry = this ?: throw AssertionError("missing entry")

    private fun SyncProgress?.required(): SyncProgress = this ?: throw AssertionError("missing progress")

    private fun SyncSnapshot?.required(): SyncSnapshot = this ?: throw AssertionError("missing snapshot")
}
