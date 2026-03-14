package com.everythingclient.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import com.everythingclient.app.data.local.dao.ServerProfileDao
import com.everythingclient.app.data.model.ServerProfile
import com.everythingclient.app.data.remote.EverythingApi
import com.everythingclient.app.data.remote.EverythingItem
import com.everythingclient.app.data.repository.DownloadRepository
import com.everythingclient.app.data.repository.ServerRepository
import com.everythingclient.app.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SearchUiEvent {
    data object FocusSearch : SearchUiEvent()
    data class ShowSnackbar(val message: String) : SearchUiEvent()
    data class ConfirmFolderDownload(val item: EverythingItem) : SearchUiEvent()
}

private data class SearchParams(
    val query: String,
    val sortField: String,
    val ascending: Int,
    val profileId: Long?,
    val regex: Boolean = false,
    val matchCase: Boolean = false,
    val matchDiacritics: Boolean = false,
    val matchWholeWord: Boolean = false,
    val matchPath: Boolean = false,
)

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
private data class SearchBaseOptions(
    val recursive: Boolean,
    val regex: Boolean,
    val sort: SortOrder,
)
private data class MatchFlags(
    val matchCase: Boolean,
    val matchDiacritics: Boolean,
    val matchWholeWord: Boolean,
    val matchPath: Boolean,
)
private data class SearchOptionFlags(
    val recursive: Boolean,
    val regex: Boolean,
    val sort: SortOrder,
    val matchCase: Boolean,
    val matchDiacritics: Boolean,
    val matchWholeWord: Boolean,
    val matchPath: Boolean,
    val matchPrefix: Boolean,
    val matchSuffix: Boolean,
    val ignorePunctuation: Boolean,
    val ignoreWhitespace: Boolean,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val everythingApi: EverythingApi,
    private val serverRepository: ServerRepository,
    private val downloadRepository: DownloadRepository,
    serverProfileDao: ServerProfileDao,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filter: StateFlow<SearchFilter> = settingsRepository.searchFilter
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchFilter.EVERYTHING)

    val searchInCurrentFolder: StateFlow<Boolean> = settingsRepository.searchInFolder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isRecursive: StateFlow<Boolean> = settingsRepository.searchRecursive
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isRegex: StateFlow<Boolean> = settingsRepository.searchRegex
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val matchCase: StateFlow<Boolean> = settingsRepository.searchMatchCase
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val matchDiacritics: StateFlow<Boolean> = settingsRepository.searchMatchDiacritics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val matchWholeWord: StateFlow<Boolean> = settingsRepository.searchMatchWholeWord
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val matchPath: StateFlow<Boolean> = settingsRepository.searchMatchPath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val matchPrefix: StateFlow<Boolean> = settingsRepository.searchMatchPrefix
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val matchSuffix: StateFlow<Boolean> = settingsRepository.searchMatchSuffix
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val ignorePunctuation: StateFlow<Boolean> = settingsRepository.searchIgnorePunctuation
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val ignoreWhitespace: StateFlow<Boolean> = settingsRepository.searchIgnoreWhitespace
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _events = MutableSharedFlow<SearchUiEvent>()
    val events = _events.asSharedFlow()

    private val _selectedItems = MutableStateFlow<Set<EverythingItem>>(emptySet())
    val selectedItems: StateFlow<Set<EverythingItem>> = _selectedItems.asStateFlow()

    val isSelectionMode: StateFlow<Boolean> = _selectedItems
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val sortOrder: StateFlow<SortOrder> = settingsRepository.sortOrder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SortOrder.NAME_ASC)

    val activeProfile: StateFlow<ServerProfile?> = serverRepository.activeProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allProfiles: StateFlow<List<ServerProfile>> = serverProfileDao.getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _totalResults = MutableStateFlow<Long?>( null)
    val totalResults: StateFlow<Long?> = _totalResults.asStateFlow()

    private val pathHistory = mutableListOf<String>()
    private val _pathHistoryCount = MutableStateFlow(0)

    val canNavigateBack: StateFlow<Boolean> = combine(
        _selectedItems, _searchQuery, _pathHistoryCount
    ) { selected, query, historyCount ->
        selected.isNotEmpty() || query.isNotEmpty() || historyCount > 0
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val pagingData: Flow<PagingData<EverythingItem>> = run {
        val queryFlow = combine(
            // Debounce only the typed query so keystroke-by-keystroke changes are
            // coalesced, while path/filter/sort changes (folder navigation, etc.)
            // fire immediately without waiting for the debounce window.
            _searchQuery.debounce(300),
            filter,
            searchInCurrentFolder,
            _currentPath,
        ) { query, filter, inFolder, path ->
            Quad(query, filter, inFolder, path)
        }

        val optionsFlow = combine(isRecursive, isRegex, sortOrder) { recursive, regex, sort ->
            SearchBaseOptions(recursive = recursive, regex = regex, sort = sort)
        }.combine(
            combine(matchCase, matchDiacritics, matchWholeWord, matchPath) { case, diacritics, wholeWord, path ->
                MatchFlags(
                    matchCase       = case,
                    matchDiacritics = diacritics,
                    matchWholeWord  = wholeWord,
                    matchPath       = path,
                )
            }
        ) { base, flags ->
            SearchOptionFlags(
                recursive       = base.recursive,
                regex           = base.regex,
                sort            = base.sort,
                matchCase       = flags.matchCase,
                matchDiacritics = flags.matchDiacritics,
                matchWholeWord  = flags.matchWholeWord,
                matchPath       = flags.matchPath,
                matchPrefix       = false,
                matchSuffix       = false,
                ignorePunctuation = false,
                ignoreWhitespace  = false,
            )
        }.combine(
            combine(matchPrefix, matchSuffix, ignorePunctuation, ignoreWhitespace) { prefix, suffix, punct, ws ->
                prefix to Triple(suffix, punct, ws)
            }
        ) { base, (prefix, rest) ->
            val (suffix, punct, ws) = rest
            base.copy(
                matchPrefix       = prefix,
                matchSuffix       = suffix,
                ignorePunctuation = punct,
                ignoreWhitespace  = ws,
            )
        }

        queryFlow.combine(optionsFlow) { quad, options -> quad to options }
    }.combine(
        serverRepository.activeProfile.map { it?.id }
    ) { (quad, triple), profileId ->
        SearchParams(
            query     = buildEverythingQuery(
                quad.first, quad.second, quad.third, quad.fourth, triple.recursive,
                triple.matchPrefix, triple.matchSuffix,
                triple.ignorePunctuation, triple.ignoreWhitespace,
            ),
            sortField = triple.sort.sortField,
            ascending = triple.sort.ascending,
            profileId = profileId,
            regex     = triple.regex,
            matchCase = triple.matchCase,
            matchDiacritics  = triple.matchDiacritics,
            matchWholeWord   = triple.matchWholeWord,
            matchPath        = triple.matchPath,
        )
    }
        .distinctUntilChanged()
        .flatMapLatest { params ->
            _totalResults.value = null
            Pager(
                config = PagingConfig(
                    pageSize         = SearchPagingSource.PAGE_SIZE,
                    initialLoadSize  = SearchPagingSource.PAGE_SIZE,
                    prefetchDistance = SearchPagingSource.PAGE_SIZE / 2,
                    enablePlaceholders = false
                )
            ) {
                SearchPagingSource(
                    api            = everythingApi,
                    query          = params.query,
                    sortField      = params.sortField,
                    ascending      = params.ascending,
                    regex          = params.regex,
                    matchCase      = params.matchCase,
                    matchDiacritics = params.matchDiacritics,
                    matchWholeWord  = params.matchWholeWord,
                    matchPath       = params.matchPath,
                    onTotalResults = { _totalResults.value = it },
                )
            }.flow
        }
        .cachedIn(viewModelScope)

    private val _profileState = MutableStateFlow<ProfileState>(ProfileState.Loading)
    val profileState: StateFlow<ProfileState> = _profileState.asStateFlow()

    init {
        viewModelScope.launch {
            serverRepository.activeProfile.collectLatest { profile ->
                _profileState.value = if (profile != null) ProfileState.Ready else ProfileState.NoProfile
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        // Do NOT clear _currentPath here. The path and query are independent
        // inputs — buildEverythingQuery handles scoping correctly based on the
        // inCurrentFolder flag. Clearing path here would break toggling
        // "search in folder" / filter while at a drive root (C:\, D:\), because
        // those paths are non-empty but still need to be preserved so the scope
        // can be restored when the toggle is switched back on.
    }
    fun onSearchInCurrentFolderChange(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSearchInFolder(enabled) }
    }
    fun onFilterChange(filter: SearchFilter) {
        viewModelScope.launch { settingsRepository.setSearchFilter(filter) }
    }
    fun onRecursiveChange(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSearchRecursive(enabled) }
    }
    fun onRegexChange(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSearchRegex(enabled) }
    }
    fun onMatchCaseChange(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSearchMatchCase(enabled) }
    }
    fun onMatchDiacriticsChange(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSearchMatchDiacritics(enabled) }
    }
    fun onMatchWholeWordChange(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSearchMatchWholeWord(enabled) }
    }
    fun onMatchPathChange(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSearchMatchPath(enabled) }
    }
    fun onMatchPrefixChange(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSearchMatchPrefix(enabled) }
    }
    fun onMatchSuffixChange(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSearchMatchSuffix(enabled) }
    }
    fun onIgnorePunctuationChange(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSearchIgnorePunctuation(enabled) }
    }
    fun onIgnoreWhitespaceChange(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSearchIgnoreWhitespace(enabled) }
    }

    fun onSortOrderChange(order: SortOrder) {
        viewModelScope.launch { settingsRepository.setSortOrder(order) }
    }

    fun switchProfile(id: Long) {
        viewModelScope.launch {
            serverRepository.setActiveProfile(id)
            _currentPath.value = ""
            pathHistory.clear()
            _pathHistoryCount.value = 0
            _searchQuery.value = ""
            _selectedItems.value = emptySet()
        }
    }

    fun loadPath(path: String, addToHistory: Boolean = true) {
        if (addToHistory && _currentPath.value != path) {
            pathHistory.add(_currentPath.value)
            _pathHistoryCount.value = pathHistory.size
        }
        _currentPath.value = path
        _searchQuery.value = ""
        _selectedItems.value = emptySet()
        viewModelScope.launch { _events.emit(SearchUiEvent.FocusSearch) }
    }

    fun onFolderClick(item: EverythingItem) {
        loadPath(item.fullPath)
    }

    fun toggleSelection(item: EverythingItem) {
        _selectedItems.update { if (item in it) it - item else it + item }
    }

    // Snapshot of currently loaded paged items — updated by the screen
    // so selectAll/selectInverse can operate on what's visible.
    private val _currentItems = MutableStateFlow<List<EverythingItem>>(emptyList())

    fun updateVisibleItems(items: List<EverythingItem>) {
        _currentItems.value = items
    }

    fun selectAll() {
        _selectedItems.value = _currentItems.value.toSet()
    }

    fun selectInverse() {
        val current = _selectedItems.value
        _selectedItems.value = _currentItems.value.filter { it !in current }.toSet()
    }

    fun clearSelection() { _selectedItems.value = emptySet() }

    fun downloadSelected() {
        viewModelScope.launch {
            val items = _selectedItems.value.toList()
            clearSelection()
            try {
                items.forEach { downloadRepository.enqueueDownload(it) }
                _events.emit(SearchUiEvent.ShowSnackbar("${items.size} added to queue"))
            } catch (e: Exception) {
                _events.emit(SearchUiEvent.ShowSnackbar("Failed to add to queue: ${e.message}"))
            }
        }
    }

    fun enqueueDownload(item: EverythingItem) {
        viewModelScope.launch {
            try {
                downloadRepository.enqueueDownload(item)
                _events.emit(SearchUiEvent.ShowSnackbar("Added to queue"))
            } catch (e: Exception) {
                _events.emit(SearchUiEvent.ShowSnackbar("Failed to add to queue: ${e.message}"))
            }
        }
    }

    fun confirmFolderDownload(item: EverythingItem) {
        viewModelScope.launch {
            try {
                downloadRepository.enqueueDownload(item)
                _events.emit(SearchUiEvent.ShowSnackbar("Added to queue"))
            } catch (e: Exception) {
                _events.emit(SearchUiEvent.ShowSnackbar("Failed to add to queue: ${e.message}"))
            }
        }
    }

    fun navigateBack(): Boolean {
        if (_selectedItems.value.isNotEmpty()) { clearSelection(); return true }
        // If there is an active search query, clear it and restore the previous
        // folder path so the list immediately shows correct folder contents.
        if (_searchQuery.value.isNotEmpty()) {
            // Just clear the query; _currentPath is already correct because
            // onSearchQueryChange no longer pushes to pathHistory.
            _searchQuery.value = ""
            return true
        }
        if (pathHistory.isNotEmpty()) {
            val prev = pathHistory.removeAt(pathHistory.size - 1)
            _pathHistoryCount.value = pathHistory.size
            _currentPath.value = prev
            return true
        }
        return false
    }

    /**
     * Builds the per-term modifier chain string from enabled options.
     * Modifiers can be chained: e.g. prefix:nopunc:term
     * These are processed by Everything's search engine directly (not HTTP-level params).
     */
    private fun buildModifierChain(
        matchPrefix: Boolean,
        matchSuffix: Boolean,
        ignorePunctuation: Boolean,
        ignoreWhitespace: Boolean,
    ): String = buildString {
        if (matchPrefix)       append("prefix:")
        if (matchSuffix)       append("suffix:")
        if (ignorePunctuation) append("nopunc:")
        if (ignoreWhitespace)  append("nows:")
    }

    /**
     * Applies the modifier chain to each plain-text token in the query,
     * leaving function operators (containing ':'), quoted strings, negations,
     * and grouping characters untouched.
     *
     * Examples:
     *   "spiderman"       → "prefix:nopunc:spiderman"
     *   "spider man"      → "prefix:nopunc:spider prefix:nopunc:man"
     *   "ext:mp3 iron man"→ "ext:mp3 prefix:nopunc:iron prefix:nopunc:man"
     *   "\"spider man\""  → "\"spider man\""  (quoted — left as-is)
     */
    private fun applyModifiersToQuery(query: String, modChain: String): String {
        if (modChain.isEmpty() || query.isBlank()) return query

        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false

        for (ch in query) {
            when {
                ch == '"'          -> { inQuotes = !inQuotes; current.append(ch) }
                ch == ' ' && !inQuotes -> {
                    if (current.isNotEmpty()) { tokens.add(current.toString()); current.clear() }
                }
                else               -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())

        return tokens.joinToString(" ") { token ->
            val skip = token.contains(':')          // function operator e.g. ext:mp3
                    || token.startsWith('"')        // quoted string
                    || token.startsWith('!')        // negation
                    || token.startsWith('<')        // group open
                    || token.startsWith('>')        // group close
                    || token.startsWith('|')        // OR operator
                    || token.startsWith('*')        // wildcard-only token
            if (skip) token else "$modChain$token"
        }
    }

    private fun buildEverythingQuery(
        baseQuery:        String,
        filter:           SearchFilter,
        inCurrentFolder:  Boolean,
        path:             String,
        recursive:        Boolean,
        matchPrefix:      Boolean = false,
        matchSuffix:      Boolean = false,
        ignorePunctuation: Boolean = false,
        ignoreWhitespace:  Boolean = false,
    ): String {
        val filterQuery = when (filter) {
            SearchFilter.EVERYTHING -> ""
            SearchFilter.VIDEO      -> "video:"
            SearchFilter.AUDIO      -> "audio:"
            SearchFilter.IMAGE      -> "pic:"
            SearchFilter.DOCUMENT   -> "doc:"
            SearchFilter.ARCHIVE    -> "zip:"
            SearchFilter.EXECUTABLE -> "exe:"
            SearchFilter.FOLDER     -> "folder:"
        }

        val modChain = buildModifierChain(matchPrefix, matchSuffix, ignorePunctuation, ignoreWhitespace)

        val sb = StringBuilder()
        if (baseQuery.isEmpty()) {
            // Browsing a root location - apply the filter prefix before the scope.
            // (Mirrors the old client-side behavior, now done server-side
            // so it works correctly with the paging data source.)
            if (filterQuery.isNotEmpty() && filter != SearchFilter.FOLDER) {
                sb.append("$filterQuery ")
            }
            val pathScope = when {
                path.isEmpty() -> "roots:"
                recursive      -> "path:\"$path\\\""
                else           -> "parent:\"$path\""
            }
            // For the folder filter while browsing, the scope already limits
            // to folders, so no extra prefix is needed.
            when (filter) {
                SearchFilter.FOLDER ->
                    if (path.isEmpty()) sb.append(pathScope) // roots: already returns only top-level entries; keep as-is.
                    else sb.append("folder: $pathScope")     // Limit to folders within the current directory.
                else -> sb.append(pathScope)
            }
        } else {
            // Active search query - scope to the current location if requested, then apply filter.
            // The backend expects a bare prefix for location scoping; using the parent operator
            // can return zero results at the top level and behave differently for nested levels.
            if (inCurrentFolder && path.isNotEmpty()) sb.append("\"$path\\\" ")
            if (filterQuery.isNotEmpty()) sb.append("$filterQuery ")
            // Apply per-term modifiers only to the user-typed portion of the query.
            sb.append(applyModifiersToQuery(baseQuery, modChain))
        }
        return sb.toString().trim()
    }
}

sealed class ProfileState {
    data object Loading   : ProfileState()
    data object NoProfile : ProfileState()
    data object Ready     : ProfileState()
}

enum class SearchFilter {
    EVERYTHING, VIDEO, AUDIO, IMAGE, DOCUMENT, ARCHIVE, EXECUTABLE, FOLDER
}

enum class SortOrder(val label: String, val shortLabel: String, val sortField: String, val ascending: Int) {
    NAME_ASC          ("Name (A-Z)",             "Name ↑",  "name",          1),
    NAME_DESC         ("Name (Z-A)",             "Name ↓",  "name",          0),
    PATH_ASC          ("Path (A-Z)",             "Path ↑",  "path",          1),
    PATH_DESC         ("Path (Z-A)",             "Path ↓",  "path",          0),
    SIZE_ASC          ("Size (Smallest)",        "Size ↑",  "size",          1),
    SIZE_DESC         ("Size (Largest)",         "Size ↓",  "size",          0),
    DATE_MODIFIED_ASC ("Date Modified (Oldest)", "Date ↑",  "date_modified", 1),
    DATE_MODIFIED_DESC("Date Modified (Newest)", "Date ↓",  "date_modified", 0),
    TYPE_ASC          ("Type (A-Z)",             "Type ↑",  "extension",     1),
    TYPE_DESC         ("Type (Z-A)",             "Type ↓",  "extension",     0),
}
