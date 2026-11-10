package com.esper.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.esper.android.data.model.*

@Database(
    entities = [
        NotebookEntity::class,
        PageEntity::class,
        CanvasEntity::class,
        QuestionEntity::class,
        ShelfEntity::class
    ],
    version = 7,
    exportSchema = true
)
abstract class EsperDatabase : RoomDatabase() {
    abstract fun dao(): EsperDao
}
