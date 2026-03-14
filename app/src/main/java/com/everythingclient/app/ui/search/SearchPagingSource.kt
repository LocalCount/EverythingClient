package com.everythingclient.app.ui.search

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.everythingclient.app.data.remote.EverythingApi
import com.everythingclient.app.data.remote.EverythingItem

/**
 * Offset-based PagingSource for the Everything HTTP API.
 *
 * For the root-drives page (empty path, no search query) we try three
 * queries in sequence until one returns results — matching the original
 * fallback logic that existed before Paging 3.
 */
class SearchPagingSource(
    private val api: EverythingApi,
    private val query: String,
    private val sortField: String,
    private val ascending: Int,
    private val regex: Boolean = false,
    private val matchCase: Boolean = false,
    private val matchDiacritics: Boolean = false,
    private val matchWholeWord: Boolean = false,
    private val matchPath: Boolean = false,
    private val onTotalResults: (Long) -> Unit = {},
) : PagingSource<Int, EverythingItem>() {

    /** True when this source is the root-drives listing (not a search). */
    private val isRootQuery = query == "roots:"

    override fun getRefreshKey(state: PagingState<Int, EverythingItem>): Int? {
        return state.anchorPosition?.let { anchor ->
            val page = state.closestPageToPosition(anchor)
            page?.prevKey?.plus(PAGE_SIZE) ?: page?.nextKey?.minus(PAGE_SIZE)
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, EverythingItem> {
        val offset = params.key ?: 0
        return try {
            val items: List<EverythingItem>
            val total: Long

            if (isRootQuery && offset == 0) {
                val result = loadRootDrives(params.loadSize)
                items = result.first
                total = result.second
                onTotalResults(total)
            } else {
                val response = api.search(
                    query     = query,
                    sort      = sortField,
                    ascending = ascending,
                    offset    = offset,
                    count     = params.loadSize,
                    regex           = if (regex) 1 else 0,
                    matchCase       = if (matchCase) 1 else 0,
                    matchWholeWord  = if (matchWholeWord) 1 else 0,
                    matchPath       = if (matchPath) 1 else 0,
                    matchDiacritics = if (matchDiacritics) 1 else 0,
                )
                items = response.results ?: emptyList()
                total = response.totalResults ?: items.size.toLong()
                if (offset == 0) onTotalResults(total)
            }

            val nextOffset = offset + items.size
            LoadResult.Page(
                data    = items,
                prevKey = if (offset == 0) null else (offset - PAGE_SIZE).coerceAtLeast(0),
                nextKey = if (nextOffset >= total) null else nextOffset
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    private suspend fun loadRootDrives(count: Int): Pair<List<EverythingItem>, Long> {
        val r1 = api.search(
            query,
            sort      = sortField,
            ascending = ascending,
            offset    = 0,
            count     = count,
            regex           = if (regex) 1 else 0,
            matchCase       = if (matchCase) 1 else 0,
            matchWholeWord  = if (matchWholeWord) 1 else 0,
            matchPath       = if (matchPath) 1 else 0,
            matchDiacritics = if (matchDiacritics) 1 else 0,
        )
        val items1 = r1.results ?: emptyList()
        if (items1.isNotEmpty()) return items1 to (r1.totalResults ?: items1.size.toLong())

        val r2 = api.search(
            "folder: parent:\"\"",
            sort      = sortField,
            ascending = ascending,
            offset    = 0,
            count     = count,
            matchCase       = if (matchCase) 1 else 0,
            matchWholeWord  = if (matchWholeWord) 1 else 0,
            matchPath       = if (matchPath) 1 else 0,
            matchDiacritics = if (matchDiacritics) 1 else 0,
        )
        val items2 = r2.results ?: emptyList()
        if (items2.isNotEmpty()) return items2 to (r2.totalResults ?: items2.size.toLong())

        val r3 = api.search(
            "path:^$",
            sort      = sortField,
            ascending = ascending,
            offset    = 0,
            count     = 100,
            matchCase       = if (matchCase) 1 else 0,
            matchWholeWord  = if (matchWholeWord) 1 else 0,
            matchPath       = if (matchPath) 1 else 0,
            matchDiacritics = if (matchDiacritics) 1 else 0,
        )
        val items3 = (r3.results ?: emptyList()).filter { it.isFolder }
        return items3 to items3.size.toLong()
    }

    companion object {
        const val PAGE_SIZE = 50
    }
}
