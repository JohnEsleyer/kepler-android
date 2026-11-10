package com.esper.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.esper.android.data.model.*

@Database(
    entities = [
        WorkspaceEntity::class,
        NotebookEntity::class,
        PageEntity::class,
        CanvasEntity::class,
        QuestionEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class EsperDatabase : RoomDatabase() {
    abstract fun dao(): EsperDao
}
