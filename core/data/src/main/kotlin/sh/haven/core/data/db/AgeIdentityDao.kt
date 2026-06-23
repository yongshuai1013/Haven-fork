package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.AgeIdentityEntity

@Dao
interface AgeIdentityDao {

    @Query("SELECT * FROM age_identities ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AgeIdentityEntity>>

    @Query("SELECT * FROM age_identities ORDER BY createdAt DESC")
    suspend fun getAll(): List<AgeIdentityEntity>

    @Query("SELECT * FROM age_identities WHERE id = :id")
    suspend fun getById(id: String): AgeIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(identity: AgeIdentityEntity)

    @Query("DELETE FROM age_identities WHERE id = :id")
    suspend fun deleteById(id: String)
}
