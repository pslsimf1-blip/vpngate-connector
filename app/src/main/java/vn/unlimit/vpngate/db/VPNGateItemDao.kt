package vn.unlimit.vpngate.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import vn.unlimit.vpngate.models.VPNGateItem

@Dao
interface VPNGateItemDao {
    @Query("SELECT * FROM vpngateitem")
    fun getAll(): List<VPNGateItem>

    @Query("SELECT * FROM vpngateitem WHERE isVerified = 1")
    fun getVerified(): List<VPNGateItem>

    @Query("SELECT COUNT(hostName) FROM vpngateitem WHERE isVerified = 1")
    fun countVerified(): Int

    @Query("UPDATE vpngateitem SET isVerified = 0")
    fun resetAllVerified()

    @RawQuery
    fun filterAndSort(query: SupportSQLiteQuery): List<VPNGateItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(vararg vpnGateItem: VPNGateItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(item: VPNGateItem)

    @Query("DELETE FROM vpngateitem")
    fun deleteAll()

    @Query("SELECT COUNT(hostName) FROM vpngateitem")
    fun count(): Int
}