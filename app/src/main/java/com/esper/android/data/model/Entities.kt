package com.esper.android.data.model

import androidx.room.*

@Entity(tableName = "workspaces")
data class WorkspaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val treeUriString: String?,
    val isDefault: Boolean = false,
    val lastAccessedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "notebooks")
data class NotebookEntity(
    @PrimaryKey val id: String,
    val workspaceId: String = "default",
    val title: String,
    val description: String,
    val ownerSub: String = "local",
    val visibility: String = "public",
    val coverPreset: String = "celestial",
    val coverImage: String? = null,
    val showTitleOnCover: Boolean = true,
    val tagsCsv: String = "",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val notesUpdatedAt: Long = 0L
)

@Entity(
    tableName = "pages",
    primaryKeys = ["notebookId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("notebookId"), Index("position")]
)
data class PageEntity(
    val id: String,
    val notebookId: String,
    val parentId: String? = null,
    val title: String,
    val notes: String = "",
    val position: Int = 0
)

@Entity(
    tableName = "canvases",
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("notebookId")]
)
data class CanvasEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val kind: String, // "html" or "json"
    val title: String,
    val payloadJson: String,
    val assignedChapter: Int = 1 // 1-based index matching chapter
)

@Entity(
    tableName = "questions",
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("notebookId")]
)
data class QuestionEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val title: String,
    val answer: String,
    val position: Int = 0
)
