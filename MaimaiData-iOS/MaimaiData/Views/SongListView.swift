import SwiftUI

struct SongListView: View {
    @Environment(DataStore.self) private var store
    @State private var criteria = SongFilterCriteria()
    @State private var displayedSongs: [SongData] = []
    @State private var showFilterSheet = false
    @State private var showSearchHistory = false
    @FocusState private var isSearchFocused: Bool

    private func refreshDisplayedSongs() {
        displayedSongs = store.filteredSongs(criteria: criteria)
    }

    var body: some View {
        NavigationStack {
            ZStack(alignment: .top) {
                ScrollView {
                    LazyVStack(spacing: AppTheme.itemSpacing) {
                        SyncStatusBanner(store: store)

                        if criteria.isActive {
                            activeFilterSummaryCard
                                .transition(.opacity.combined(with: .scale(scale: 0.97, anchor: .top)))
                        }

                        if store.isLoadingLocalData && displayedSongs.isEmpty {
                            ProgressView("正在读取曲库…")
                                .frame(maxWidth: .infinity)
                                .padding(.top, 56)
                        } else if displayedSongs.isEmpty {
                            ContentUnavailableView(
                                criteria.onlyFavorites && store.favoriteIds.isEmpty ? "还没有收藏歌曲" : "未找到匹配曲目",
                                systemImage: criteria.onlyFavorites && store.favoriteIds.isEmpty ? "heart" : "magnifyingglass",
                                description: Text(
                                    criteria.onlyFavorites && store.favoriteIds.isEmpty
                                        ? "在曲目详情页点击右上角心形按钮即可收藏"
                                        : "试试修改关键词或重置筛选条件"
                                )
                            )
                            .padding(.top, 48)
                        } else {
                            ForEach(displayedSongs) { song in
                                NavigationLink(value: song) {
                                    SongRowView(song: song, isFavorite: store.isFavorite(song))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    .padding(.horizontal, AppTheme.pagePadding)
                    .padding(.vertical, AppTheme.itemSpacing)
                }
                .scrollDismissesKeyboard(.immediately)
                .safeAreaInset(edge: .top, spacing: 0) {
                    Color.clear.frame(height: 54)
                }

                VStack(spacing: 2) {
                    searchInputBar

                    if showSearchHistory && !store.searchHistory.isEmpty {
                        searchHistoryCard
                            .background(
                                .ultraThinMaterial,
                                in: RoundedRectangle(cornerRadius: AppTheme.Radius.card, style: .continuous)
                            )
                            .padding(.horizontal, AppTheme.pagePadding)
                            .shadow(color: .black.opacity(0.10), radius: 12, y: 6)
                            .transition(
                                .asymmetric(
                                    insertion: .opacity.combined(with: .scale(scale: 0.96, anchor: .top)),
                                    removal: .opacity.combined(with: .scale(scale: 0.98, anchor: .top))
                                )
                            )
                    }
                }
            }
            .appPageBackground()
            .navigationTitle("曲目列表")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        isSearchFocused = false
                        withAnimation(.snappy(duration: 0.22)) {
                            criteria.onlyFavorites.toggle()
                        }
                    } label: {
                        Image(systemName: criteria.onlyFavorites ? "heart.fill" : "heart")
                            .foregroundStyle(criteria.onlyFavorites ? AppTheme.favoritePink : .primary)
                            .symbolEffect(.bounce, value: criteria.onlyFavorites)
                    }
                    .sensoryFeedback(.impact(weight: .light), trigger: criteria.onlyFavorites)
                    .accessibilityLabel(criteria.onlyFavorites ? "取消收藏筛选" : "仅显示收藏歌曲")
                    .accessibilityValue(criteria.onlyFavorites ? "已开启" : "未开启")

                    Button {
                        isSearchFocused = false
                        showFilterSheet = true
                    } label: {
                        Image(
                            systemName: criteria.isActive
                                ? "line.3.horizontal.decrease.circle.fill"
                                : "line.3.horizontal.decrease.circle"
                        )
                        .foregroundStyle(criteria.isActive ? AppTheme.accentText : .primary)
                    }
                    .accessibilityLabel("筛选")
                }
            }
            .sheet(isPresented: $showFilterSheet) {
                SearchFilterSheet(criteria: $criteria)
            }
            .navigationDestination(for: SongData.self) { song in
                SongDetailView(song: song)
            }
            .onAppear {
                if displayedSongs.isEmpty && !store.songs.isEmpty {
                    refreshDisplayedSongs()
                }
            }
            .onDisappear {
                if !criteria.keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    store.addSearchHistory(criteria.keyword)
                }
                // 若键盘弹起时直接点进歌曲详情页，无动画收起焦点与最近搜索，避免返回时页面跳动
                if isSearchFocused || showSearchHistory {
                    var transaction = Transaction()
                    transaction.disablesAnimations = true
                    withTransaction(transaction) {
                        isSearchFocused = false
                        showSearchHistory = false
                    }
                }
            }
            .onChange(of: isSearchFocused) { _, focused in
                withAnimation(.spring(duration: 0.22, bounce: 0)) {
                    showSearchHistory = focused
                }
            }
            .onChange(of: criteria) { _, _ in refreshDisplayedSongs() }
            .onChange(of: store.catalogRevision) { _, _ in refreshDisplayedSongs() }
            .onChange(of: store.playedSongIds) { _, _ in refreshDisplayedSongs() }
            .onChange(of: store.serverRegion) { _, newRegion in
                let pruned = SongFilter.prunedCriteria(criteria, for: newRegion)
                if pruned != criteria {
                    criteria = pruned
                } else {
                    refreshDisplayedSongs()
                }
            }
            .onChange(of: store.favoriteIds) { _, _ in
                if criteria.onlyFavorites { refreshDisplayedSongs() }
            }
            .onChange(of: store.enableAliasSearch) { _, _ in
                if !criteria.keyword.isEmpty { refreshDisplayedSongs() }
            }
            .onChange(of: store.enableCharterSearch) { _, _ in
                if !criteria.keyword.isEmpty { refreshDisplayedSongs() }
            }
        }
    }

    private var searchInputBar: some View {
        HStack(spacing: AppTheme.Space.s) {
            Image(systemName: "magnifyingglass")
                .font(AppTheme.Typography.rowTitle)
                .foregroundStyle(.secondary)

            TextField("搜索曲名、别名、谱师或 ID", text: $criteria.keyword)
                .textFieldStyle(.plain)
                .font(AppTheme.Typography.body)
                .focused($isSearchFocused)
                .submitLabel(.search)
                .onSubmit {
                    store.addSearchHistory(criteria.keyword)
                    isSearchFocused = false
                }

            if !criteria.keyword.isEmpty {
                Button {
                    criteria.keyword = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(AppTheme.Typography.body)
                        .foregroundStyle(.secondary)
                        .padding(AppTheme.Space.xs)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("清除搜索词")
            }
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 42)
        .contentShape(Capsule())
        .onTapGesture {
            isSearchFocused = true
        }
        .appGlassCapsule()
        .padding(.horizontal, AppTheme.pagePadding)
        .padding(.top, 6)
        .padding(.bottom, 6)
    }

    private var searchHistoryCard: some View {
        AppCard(padding: AppTheme.compactCardPadding, tint: AppTheme.accent) {
            VStack(alignment: .leading, spacing: AppTheme.Space.s) {
                HStack {
                    Text("最近搜索")
                        .font(AppTheme.Typography.inlineAction)
                        .foregroundStyle(.secondary)
                    Spacer()
                    AppInlineButton(title: "清空", tint: .secondary) {
                        withAnimation(.snappy(duration: 0.22)) {
                            store.clearSearchHistory()
                        }
                    }
                }

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(store.searchHistory.prefix(12), id: \.self) { term in
                            FilterCapsuleChip(title: term, isSelected: false) {
                                criteria.keyword = term
                                store.addSearchHistory(term)
                                isSearchFocused = false
                            }
                            .accessibilityLabel("搜索历史：\(term)")
                        }
                    }
                }
            }
        }
    }

    private var activeFilterSummaryCard: some View {
        AppCard(padding: AppTheme.compactCardPadding, tint: AppTheme.accent) {
            VStack(alignment: .leading, spacing: AppTheme.Space.s) {
                HStack {
                    Text("已筛选 \(displayedSongs.count) 首曲目")
                        .font(AppTheme.Typography.inlineAction)
                        .foregroundStyle(.secondary)
                    Spacer()
                    AppInlineButton(title: "重置", icon: "arrow.counterclockwise") {
                        withAnimation(.snappy(duration: 0.22)) {
                            let currentKeyword = criteria.keyword
                            var reset = SongFilterCriteria()
                            reset.keyword = currentKeyword
                            criteria = reset
                        }
                    }
                }

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        if criteria.onlyFavorites {
                            filterBadge("仅看收藏", tint: AppTheme.favoritePink) {
                                criteria.onlyFavorites = false
                            }
                        }
                        if criteria.sortOption != .defaultDesc {
                            filterBadge(criteria.sortOption.rawValue) {
                                criteria.sortOption = .defaultDesc
                            }
                        }
                        if criteria.useExactDS {
                            filterBadge(String(format: "定数 %.1f", criteria.exactDS)) {
                                criteria.useExactDS = false
                                criteria.selectedLevel = "ALL"
                            }
                        } else if criteria.selectedLevel != "ALL" {
                            filterBadge("等级 \(criteria.selectedLevel)") {
                                criteria.selectedLevel = "ALL"
                            }
                        }
                        ForEach(Array(criteria.selectedGenres).sorted(), id: \.self) { genre in
                            filterBadge(genre, tint: MaimaiConstants.genreColors(for: genre).bg) {
                                criteria.selectedGenres.remove(genre)
                            }
                        }
                        ForEach(Array(criteria.selectedVersions).sorted(), id: \.self) { ver in
                            filterBadge(ver) {
                                criteria.selectedVersions.remove(ver)
                            }
                        }
                    }
                }
            }
        }
    }

    private func filterBadge(
        _ text: String,
        tint: Color = AppTheme.accent,
        onRemove: @escaping () -> Void
    ) -> some View {
        AppCapsuleLabel(text: text, tint: tint, style: .filter) {
            withAnimation(.snappy(duration: 0.22)) {
                onRemove()
            }
        }
    }
}

private struct SongRowView: View {
    let song: SongData
    let isFavorite: Bool

    var body: some View {
        let colors = MaimaiConstants.genreColors(for: song.basicInfo.genre)
        AppCard(padding: AppTheme.compactCardPadding, tint: colors.bg) {
            HStack(alignment: .center, spacing: AppTheme.Space.m) {
                SongCoverBadgeView(
                    song: song,
                    size: .list,
                    borderColor: colors.stroke
                )

                VStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .top, spacing: 6) {
                        Text(song.title)
                            .font(AppTheme.Typography.songTitle)
                            .foregroundStyle(.primary)
                            .lineLimit(2)
                            .fixedSize(horizontal: false, vertical: true)

                        if isFavorite {
                            Image(systemName: "heart.fill")
                                .font(AppTheme.Typography.caption2)
                                .foregroundStyle(AppTheme.favoritePink)
                                .padding(.top, 3)
                        }

                        Spacer(minLength: 4)

                        ChartTypeBadge(isDX: song.isDX)
                            .padding(.top, 2)
                    }
                    .padding(.bottom, 5)

                    HStack(spacing: 6) {
                        GenreCapsuleTag(genre: song.basicInfo.genre)
                        if song.isDisabled == true {
                            AppCapsuleLabel(text: "已删除", tint: .secondary, style: .compact)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.bottom, 7)

                    FlowLayout(spacing: 5, lineSpacing: 4) {
                        ForEach(song.resolvedCharts) { chart in
                            DifficultyLevelBadge(
                                difficulty: chart.difficulty,
                                text: song.isUtage ? chart.level : String(format: "%.1f", chart.ds)
                            )
                        }
                    }
                }
            }
        }
    }
}
