package com.nextcloud.musicplayer.ui.albums

enum class AlbumViewMode(val id: Int, val title: String, val columns: Int) {
    LIST(0, "列表模式", 0),
    GRID_1(1, "1 欄 (大縮圖)", 1),
    GRID_2(2, "2 欄 (預設網格)", 2),
    GRID_3(3, "3 欄 (中縮圖)", 3),
    GRID_4(4, "4 欄 (小縮圖)", 4);

    val isGrid: Boolean get() = this != LIST

    companion object {
        fun fromId(id: Int): AlbumViewMode = entries.firstOrNull { it.id == id } ?: GRID_2
    }
}
