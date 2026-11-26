package com.nek0ayaya.gradentranslator.hook

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Target-app private cache. The API key is never stored in this database. */
internal class TranslationCache(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "gradentranslator-cache.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE translations (
                source TEXT NOT NULL,
                target_language TEXT NOT NULL,
                translated TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(source, target_language)
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 2 adds target_language to the key. Old prototype entries had no language tag,
        // so dropping them is safer than serving a translation in the wrong language.
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS translations")
            onCreate(db)
        }
    }

    fun getTranslation(source: String, targetLanguage: String): String? {
        readableDatabase.rawQuery(
            "SELECT translated FROM translations WHERE source = ? AND target_language = ? LIMIT 1",
            arrayOf(source, targetLanguage)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun putTranslations(values: Map<String, String>, targetLanguage: String) {
        if (values.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            values.forEach { (source, translated) ->
                val row = ContentValues().apply {
                    put("source", source)
                    put("target_language", targetLanguage)
                    put("translated", translated)
                    put("updated_at", now)
                }
                db.insertWithOnConflict(
                    "translations",
                    null,
                    row,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
