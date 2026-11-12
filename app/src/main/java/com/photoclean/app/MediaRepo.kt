package com.photoclean.app

import android.content.ContentResolver
import android.content.ContentUris
import android.os.Bundle
import android.provider.MediaStore

/** MediaStore 에서 사진 목록 읽기. */
object MediaRepo {

    /** trashedOnly = true 면 휴지통에 있는 사진만, 최근에 지운 순서로. */
    fun query(cr: ContentResolver, trashedOnly: Boolean = false): List<Photo> {
        val result = ArrayList<Photo>()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_EXPIRES,
            MediaStore.MediaColumns.IS_FAVORITE,
        )
        val sort = if (trashedOnly) {
            // 휴지통 기한 = 지운 시각 + 30일 → 기한이 늦을수록 최근에 지운 것
            "${MediaStore.MediaColumns.DATE_EXPIRES} DESC"
        } else {
            "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
        }
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
            if (trashedOnly) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val cursor = try {
            cr.query(collection, projection, args, null)
        } catch (e: Exception) {
            null
        }
        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val bucketCol = c.getColumnIndex(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameCol = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val expCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_EXPIRES)
            val favCol = c.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val taken = c.getLong(takenCol)
                result.add(
                    Photo(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = c.getString(nameCol) ?: "",
                        dateMillis = if (taken > 0) taken else c.getLong(addedCol) * 1000,
                        size = c.getLong(sizeCol),
                        bucketId = if (bucketCol >= 0) c.getString(bucketCol) ?: "" else "",
                        bucketName = if (bucketNameCol >= 0) c.getString(bucketNameCol) ?: "" else "",
                        expiresMillis = if (expCol >= 0) c.getLong(expCol) * 1000 else 0,
                        favorite = favCol >= 0 && c.getInt(favCol) == 1,
                    )
                )
            }
        }
        return result
    }
}
