package io.nekohasekai.sagernet.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One "this SIM uses that front proxy" binding.
 *
 * The rows are global rather than per group: the front proxy of a group is a fixed slot
 * ([ProxyGroup.frontProxyId]), and this table lets the running SIM override that slot. When the
 * active data SIM matches [slot] (and optionally [carrier]) the value of [profileId] is used as the
 * front proxy of *every* group, so switching SIM cards switches the front proxy without touching
 * anything else.
 *
 * Identification is the SIM slot first (1-based, `SIM 1` / `SIM 2`) and the carrier second. A row
 * with an empty [carrier] matches any operator in that slot; a row with a carrier matches only that
 * one, which keeps working when the same physical SIM moves to the other slot.
 */
@Entity(tableName = SimFrontProxyEntity.TABLE)
data class SimFrontProxyEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    /** 1-based SIM slot, the value TelephonyManager reports as simSlotIndex + 1. */
    @ColumnInfo(name = "slot", defaultValue = "-1") var slot: Int = -1,
    /** MCC+MNC of the subscription, "" = any carrier in this slot. */
    @ColumnInfo(name = "carrier", defaultValue = "") var carrier: String = "",
    /** The profile used as the front proxy; -1 (or any id <= 0) = let the group decide. */
    @ColumnInfo(name = "profile_id", defaultValue = "-1") var profileId: Long = -1L,
    /** Stored order for the editor. */
    @ColumnInfo(name = "display_order", defaultValue = "0") var displayOrder: Long = 0L,
) {

    /** What the settings screen shows for the carrier part. */
    fun carrierLabel(): String = carrier.ifEmpty { ANY_CARRIER }

    companion object {
        const val TABLE = "sim_front_proxies"

        /** Shown for a row that matches every operator of its slot. */
        const val ANY_CARRIER = "*"

        /** A slot of 0 (or less) is "not a SIM binding". */
        fun validSlot(slot: Int): Boolean = slot > 0
    }

    @androidx.room.Dao
    interface Dao {

        @Query("SELECT * FROM `sim_front_proxies` ORDER BY `display_order`, `id`")
        fun all(): List<SimFrontProxyEntity>

        @Query("SELECT * FROM `sim_front_proxies` WHERE `slot` = :slot")
        fun bySlot(slot: Int): List<SimFrontProxyEntity>

        @Query("SELECT * FROM `sim_front_proxies` WHERE `id` = :id")
        fun getById(id: Long): SimFrontProxyEntity?

        /** Room's autoGenerate treats 0 as "assign one"; [id] must be cleared before an insert. */
        @Insert
        fun insert(binding: SimFrontProxyEntity): Long

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        fun upsert(binding: SimFrontProxyEntity)

        @Query("UPDATE `sim_front_proxies` SET `display_order` = :order WHERE `id` = :id")
        fun setDisplayOrder(id: Long, order: Long)

        @Query("SELECT COALESCE(MAX(`display_order`), 0) + 1 FROM `sim_front_proxies`")
        fun nextDisplayOrder(): Long

        @Query("DELETE FROM `sim_front_proxies` WHERE `id` = :id")
        fun deleteById(id: Long): Int

        @Query("DELETE FROM `sim_front_proxies`")
        fun reset()
    }
}
