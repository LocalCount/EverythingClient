package com.everythingclient.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.everythingclient.app.ui.search.SearchFilter
import com.everythingclient.app.ui.search.SortOrder
import com.everythingclient.app.ui.theme.AppTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val themeKey       = stringPreferencesKey("theme")
    private val amoledKey      = booleanPreferencesKey("amoled_enabled")
    private val downloadPathKey = stringPreferencesKey("download_path")
    private val sortOrderKey = stringPreferencesKey("sort_order")
    private val concurrentDownloadsKey = intPreferencesKey("concurrent_downloads")
    private val downloadThreadsKey = intPreferencesKey("download_threads")
    private val isFirstRunKey = booleanPreferencesKey("is_first_run")

    private val sidebarServersExpandedKey = booleanPreferencesKey("sidebar_servers_expanded")
    private val sidebarFiltersExpandedKey = booleanPreferencesKey("sidebar_filters_expanded")
    private val sidebarSortExpandedKey    = booleanPreferencesKey("sidebar_sort_expanded")
    private val sidebarOptionsExpandedKey = booleanPreferencesKey("sidebar_options_expanded")

    private val searchFilterKey          = stringPreferencesKey("search_filter")
    private val searchRecursiveKey       = booleanPreferencesKey("search_recursive")
    private val searchRegexKey           = booleanPreferencesKey("search_regex")
    private val searchInFolderKey        = booleanPreferencesKey("search_in_folder")
    private val searchMatchCaseKey         = booleanPreferencesKey("search_match_case")
    private val searchMatchDiacriticsKey   = booleanPreferencesKey("search_match_diacritics")
    private val searchMatchWholeWordKey    = booleanPreferencesKey("search_match_whole_word")
    private val searchMatchPathKey         = booleanPreferencesKey("search_match_path")
    private val searchMatchPrefixKey       = booleanPreferencesKey("search_match_prefix")
    private val searchMatchSuffixKey       = booleanPreferencesKey("search_match_suffix")
    private val searchIgnorePunctuationKey = booleanPreferencesKey("search_ignore_punctuation")
    private val searchIgnoreWhitespaceKey  = booleanPreferencesKey("search_ignore_whitespace")

    // Base theme — never stores AMOLED directly; AMOLED is a separate toggle
    val theme: Flow<AppTheme> = context.dataStore.data.map { preferences ->
        try {
            val stored = AppTheme.valueOf(preferences[themeKey] ?: AppTheme.DARK.name)
            // Migrate old AMOLED value: treat as DARK base theme
            if (stored == AppTheme.AMOLED) AppTheme.DARK else stored
        } catch (_: Exception) {
            AppTheme.DARK
        }
    }

    val amoledEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        // Also migrate: if old theme was AMOLED, treat amoled as enabled
        val oldTheme = try { AppTheme.valueOf(preferences[themeKey] ?: "") } catch (_: Exception) { null }
        preferences[amoledKey] ?: (oldTheme == AppTheme.AMOLED)
    }

    val downloadPath: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[downloadPathKey]
    }

    val isFirstRun: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[isFirstRunKey] ?: true
    }

    val sidebarServersExpanded: Flow<Boolean> = context.dataStore.data.map { it[sidebarServersExpandedKey] ?: true }
    val sidebarFiltersExpanded: Flow<Boolean> = context.dataStore.data.map { it[sidebarFiltersExpandedKey] ?: true }
    val sidebarSortExpanded:    Flow<Boolean> = context.dataStore.data.map { it[sidebarSortExpandedKey]    ?: false }
    val sidebarOptionsExpanded: Flow<Boolean> = context.dataStore.data.map { it[sidebarOptionsExpandedKey] ?: true }

    val searchFilter: Flow<SearchFilter> = context.dataStore.data.map { preferences ->
        try { SearchFilter.valueOf(preferences[searchFilterKey] ?: SearchFilter.EVERYTHING.name) }
        catch (_: Exception) { SearchFilter.EVERYTHING }
    }

    val searchRecursive: Flow<Boolean> = context.dataStore.data.map { it[searchRecursiveKey] ?: false }

    val searchRegex: Flow<Boolean> = context.dataStore.data.map { it[searchRegexKey] ?: false }

    val searchInFolder: Flow<Boolean> = context.dataStore.data.map { it[searchInFolderKey] ?: false }

    val searchMatchCase: Flow<Boolean> = context.dataStore.data.map { it[searchMatchCaseKey] ?: false }

    val searchMatchDiacritics: Flow<Boolean> = context.dataStore.data.map { it[searchMatchDiacriticsKey] ?: false }

    val searchMatchWholeWord: Flow<Boolean> = context.dataStore.data.map { it[searchMatchWholeWordKey] ?: false }

    val searchMatchPath: Flow<Boolean> = context.dataStore.data.map { it[searchMatchPathKey] ?: false }

    val searchMatchPrefix: Flow<Boolean> = context.dataStore.data.map { it[searchMatchPrefixKey] ?: false }

    val searchMatchSuffix: Flow<Boolean> = context.dataStore.data.map { it[searchMatchSuffixKey] ?: false }

    val searchIgnorePunctuation: Flow<Boolean> = context.dataStore.data.map { it[searchIgnorePunctuationKey] ?: false }

    val searchIgnoreWhitespace: Flow<Boolean> = context.dataStore.data.map { it[searchIgnoreWhitespaceKey] ?: false }

    val sortOrder: Flow<SortOrder> = context.dataStore.data.map { preferences ->
        try {
            SortOrder.valueOf(preferences[sortOrderKey] ?: SortOrder.NAME_ASC.name)
        } catch (_: Exception) {
            SortOrder.NAME_ASC
        }
    }

    val concurrentDownloads: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[concurrentDownloadsKey] ?: 3
    }

    val downloadThreads: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[downloadThreadsKey] ?: 3
    }

    suspend fun setTheme(theme: AppTheme) {
        context.dataStore.edit { preferences ->
            // Never persist AMOLED as the base theme
            preferences[themeKey] = if (theme == AppTheme.AMOLED) AppTheme.DARK.name else theme.name
        }
    }

    suspend fun setAmoledEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[amoledKey] = enabled
        }
    }

    suspend fun setDownloadPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[downloadPathKey] = path
            preferences[isFirstRunKey] = false
        }
    }

    suspend fun setFirstRunCompleted() {
        context.dataStore.edit { preferences ->
            preferences[isFirstRunKey] = false
        }
    }

    suspend fun setSearchFilter(filter: SearchFilter) {
        context.dataStore.edit { it[searchFilterKey] = filter.name }
    }

    suspend fun setSearchRecursive(recursive: Boolean) {
        context.dataStore.edit { it[searchRecursiveKey] = recursive }
    }

    suspend fun setSearchRegex(regex: Boolean) {
        context.dataStore.edit { it[searchRegexKey] = regex }
    }

    suspend fun setSearchInFolder(inFolder: Boolean) {
        context.dataStore.edit { it[searchInFolderKey] = inFolder }
    }

    suspend fun setSearchMatchCase(enabled: Boolean) {
        context.dataStore.edit { it[searchMatchCaseKey] = enabled }
    }

    suspend fun setSearchMatchDiacritics(enabled: Boolean) {
        context.dataStore.edit { it[searchMatchDiacriticsKey] = enabled }
    }

    suspend fun setSearchMatchWholeWord(enabled: Boolean) {
        context.dataStore.edit { it[searchMatchWholeWordKey] = enabled }
    }

    suspend fun setSearchMatchPath(enabled: Boolean) {
        context.dataStore.edit { it[searchMatchPathKey] = enabled }
    }

    suspend fun setSearchMatchPrefix(enabled: Boolean) {
        context.dataStore.edit { it[searchMatchPrefixKey] = enabled }
    }

    suspend fun setSearchMatchSuffix(enabled: Boolean) {
        context.dataStore.edit { it[searchMatchSuffixKey] = enabled }
    }

    suspend fun setSearchIgnorePunctuation(enabled: Boolean) {
        context.dataStore.edit { it[searchIgnorePunctuationKey] = enabled }
    }

    suspend fun setSearchIgnoreWhitespace(enabled: Boolean) {
        context.dataStore.edit { it[searchIgnoreWhitespaceKey] = enabled }
    }

    suspend fun setSortOrder(sortOrder: SortOrder) {
        context.dataStore.edit { preferences ->
            preferences[sortOrderKey] = sortOrder.name
        }
    }

    suspend fun setConcurrentDownloads(limit: Int) {
        context.dataStore.edit { preferences ->
            preferences[concurrentDownloadsKey] = limit
        }
    }

    suspend fun setDownloadThreads(threads: Int) {
        context.dataStore.edit { preferences ->
            preferences[downloadThreadsKey] = threads
        }
    }

    suspend fun setSidebarServersExpanded(expanded: Boolean) {
        context.dataStore.edit { it[sidebarServersExpandedKey] = expanded }
    }

    suspend fun setSidebarFiltersExpanded(expanded: Boolean) {
        context.dataStore.edit { it[sidebarFiltersExpandedKey] = expanded }
    }

    suspend fun setSidebarSortExpanded(expanded: Boolean) {
        context.dataStore.edit { it[sidebarSortExpandedKey] = expanded }
    }

    suspend fun setSidebarOptionsExpanded(expanded: Boolean) {
        context.dataStore.edit { it[sidebarOptionsExpandedKey] = expanded }
    }

}
