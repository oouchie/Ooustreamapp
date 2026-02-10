package com.ooustream.iptv.data.repository

import com.ooustream.iptv.data.local.dao.FavoriteDao
import com.ooustream.iptv.data.local.entity.FavoriteEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoriteRepository @Inject constructor(
    private val favoriteDao: FavoriteDao
) {
    fun getAllFavorites(): Flow<List<FavoriteEntity>> = favoriteDao.getAllFavorites()

    fun getFavoritesByType(type: String): Flow<List<FavoriteEntity>> =
        favoriteDao.getFavoritesByType(type)

    suspend fun getFavoritesListByType(type: String): List<FavoriteEntity> =
        favoriteDao.getFavoritesListByType(type)

    suspend fun isFavorite(id: String): Boolean = favoriteDao.isFavorite(id)

    suspend fun addFavorite(favorite: FavoriteEntity) = favoriteDao.insert(favorite)

    suspend fun removeFavorite(id: String) = favoriteDao.delete(id)

    fun getCount(): Flow<Int> = favoriteDao.getCount()
}
