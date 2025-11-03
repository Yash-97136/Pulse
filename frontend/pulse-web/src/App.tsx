import { useMemo, useState, useEffect } from "react";
import { AnimatePresence } from "framer-motion";
import { useTrendsQuery } from "./hooks/useTrendsQuery";
import { useAnomaliesQuery } from "./hooks/useAnomaliesQuery";
import { useKeywordDetailQuery } from "./hooks/useKeywordDetailQuery";
import { Layout } from "./components/Layout";
import { Header } from "./components/Header";
import { HeroStrip } from "./components/HeroStrip";
import { TrendExplorer } from "./components/TrendExplorer";
import { AnomalyTimeline } from "./components/AnomalyTimeline";
import { AnomalyFilters, type AnomalyFiltersState } from "./components/AnomalyFilters";
import { DetailDrawer } from "./components/DetailDrawer";
import { PageSkeleton } from "./components/PageSkeleton";
import { ErrorState } from "./components/ErrorState";
import { useAnomalyStream } from "./hooks/useAnomalyStream";
import type { AnomalyEvent, TrendMetric, TrendsResponse } from "./types";
import { fetchAnomalies, fetchTrends } from "./lib/api";

// Prefer a non-empty label; default to "Local" when unset or empty
const FALLBACK_ENV = (process.env.VITE_ENV_LABEL || "Local").toString().trim() || "Local";

export default function App() {
  const [selectedKeyword, setSelectedKeyword] = useState<string | null>(null);
  const [filters, setFilters] = useState<AnomalyFiltersState>({ keyword: "", minZ: null, since: null });
  const [page, setPage] = useState(0);
  const [allAnomalies, setAllAnomalies] = useState<AnomalyEvent[]>([]);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [hasMore, setHasMore] = useState(true);
  const [allTrends, setAllTrends] = useState<TrendMetric[]>([]);
  const [trendsOffset, setTrendsOffset] = useState(0);
  const [isLoadingMoreTrends, setIsLoadingMoreTrends] = useState(false);
  const [hasMoreTrends, setHasMoreTrends] = useState(true);
  const trendsQuery = useTrendsQuery();
  const anomaliesQuery = useAnomaliesQuery({
    page: 0,
    limit: 40,
    keyword: filters.keyword.trim() || null,
    minZ: filters.minZ,
    since: filters.since
  });
  // Reset paging when filters change
  useEffect(() => {
    setPage(0);
  }, [filters.keyword, filters.minZ, filters.since]);

  // Seed initial page into aggregated list when it changes
  useEffect(() => {
    if (anomaliesQuery.data) {
      const first = anomaliesQuery.data.anomalies;
      const byId = new Map(first.map((a) => [a.id, a] as const));
      setAllAnomalies(Array.from(byId.values()));
      setHasMore((first?.length ?? 0) >= 40);
    } else if (anomaliesQuery.isLoading) {
      setAllAnomalies([]);
      setHasMore(true);
    }
  }, [anomaliesQuery.data, anomaliesQuery.isLoading]);

  const stream = useAnomalyStream(allAnomalies, { max: 400 });

  // Seed initial trends page into aggregated trends list
  useEffect(() => {
    const tdata = trendsQuery.data as TrendsResponse | undefined;
    if (tdata) {
      const pageTrends = tdata.trends ?? [];
      // de-dupe by keyword preserving order
      const seen = new Set<string>();
      const merged: TrendMetric[] = [];
      for (const t of pageTrends) {
        if (!seen.has(t.keyword)) {
          seen.add(t.keyword);
          merged.push(t);
        }
      }
      setAllTrends(merged);
      const nextOffset = tdata.meta?.nextOffset ?? merged.length;
      setTrendsOffset(nextOffset);
      setHasMoreTrends(Boolean(tdata.meta?.hasMore ?? merged.length > 0));
    } else if (trendsQuery.isLoading) {
      setAllTrends([]);
      setTrendsOffset(0);
      setHasMoreTrends(true);
    }
  }, [trendsQuery.data, trendsQuery.isLoading]);

  const liveIds = useMemo(() => new Set(stream.liveOnly.map((e) => e.id)), [stream.liveOnly]);

  async function loadMore() {
    if (isLoadingMore || !hasMore) return;
    setIsLoadingMore(true);
    try {
      const nextPage = page + 1;
      const resp = await fetchAnomalies({
        page: nextPage,
        limit: 40,
        keyword: filters.keyword.trim() || null,
        minZ: filters.minZ,
        since: filters.since
      });
      const combined = new Map<string, AnomalyEvent>();
      for (const a of [...allAnomalies, ...resp.anomalies]) combined.set(a.id, a);
      const sorted = Array.from(combined.values()).sort((a, b) =>
        new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
      );
      setAllAnomalies(sorted);
      setPage(nextPage);
      setHasMore((resp.anomalies?.length ?? 0) >= 40);
    } finally {
      setIsLoadingMore(false);
    }
  }

  async function loadMoreTrends() {
    if (isLoadingMoreTrends || !hasMoreTrends) return;
    setIsLoadingMoreTrends(true);
    try {
      const resp = await fetchTrends({ offset: trendsOffset, limit: 60 });
      const combined = new Map<string, TrendMetric>();
      for (const t of [...allTrends, ...resp.trends]) combined.set(t.keyword, t);
      const merged = Array.from(combined.values());
      setAllTrends(merged);
      setTrendsOffset(resp.meta?.nextOffset ?? merged.length);
      setHasMoreTrends(Boolean(resp.meta?.hasMore ?? (resp.trends?.length ?? 0) > 0));
    } finally {
      setIsLoadingMoreTrends(false);
    }
  }
  const detailQuery = useKeywordDetailQuery(selectedKeyword, {
    enabled: Boolean(selectedKeyword)
  });

  const kpi = useMemo(() => {
    const tdata = trendsQuery.data as TrendsResponse | undefined;
    if (!tdata) {
      return {
        totalPosts: 0,
        activeKeywords: 0,
        anomaliesToday: anomaliesQuery.data?.anomalies.length ?? 0
      };
    }
    const totalPosts = tdata.meta?.totalPosts ?? tdata.trends.reduce((sum: number, trend: TrendMetric) => sum + trend.volume, 0);
    return {
      totalPosts,
      activeKeywords: tdata.meta?.activeKeywords ?? tdata.trends.length,
      anomaliesToday: anomaliesQuery.data?.meta?.anomaliesToday ?? anomaliesQuery.data?.anomalies.length ?? 0
    };
  }, [trendsQuery.data, anomaliesQuery.data]);

  if ((trendsQuery.isLoading || anomaliesQuery.isLoading) && !trendsQuery.data && !anomaliesQuery.data) {
    return <PageSkeleton />;
  }

  if (trendsQuery.error) {
    return <ErrorState title="Unable to fetch trends" error={trendsQuery.error} />;
  }

  if (anomaliesQuery.error) {
    return <ErrorState title="Unable to fetch anomalies" error={anomaliesQuery.error} />;
  }

  return (
    <Layout>
      <Header
        lastUpdated={trendsQuery.dataUpdatedAt}
        envLabel={FALLBACK_ENV}
        isRefreshing={trendsQuery.isFetching || anomaliesQuery.isFetching}
      />
      <main className="mx-auto flex w-full max-w-7xl flex-1 flex-col gap-8 px-4 pb-12 sm:px-6 lg:px-8">
  <HeroStrip kpi={kpi} activeWindowMinutes={(trendsQuery.data as TrendsResponse | undefined)?.meta?.windowMinutes ?? 60} />
        <div className="grid grid-cols-1 gap-6 xl:grid-cols-[2fr_1fr]">
          <TrendExplorer
            trends={allTrends}
            isLoading={trendsQuery.isLoading}
            onSelect={setSelectedKeyword}
            selectedKeyword={selectedKeyword}
            onLoadMore={loadMoreTrends}
            canLoadMore={hasMoreTrends}
            isLoadingMore={isLoadingMoreTrends}
          />
          <div className="space-y-4">
            <AnomalyFilters value={filters} onChange={setFilters} />
            <AnomalyTimeline anomalies={stream.events} isLoading={anomaliesQuery.isLoading} liveIds={liveIds} />
            <div className="flex justify-center">
              <button
                disabled={!hasMore || isLoadingMore}
                onClick={loadMore}
                className="rounded-full border border-white/10 bg-white/5 px-4 py-2 text-sm text-white disabled:opacity-50"
              >
                {isLoadingMore ? "Loading…" : hasMore ? "Load more" : "No more results"}
              </button>
            </div>
          </div>
        </div>
      </main>
      <AnimatePresence>
        {selectedKeyword && (
          <DetailDrawer
            keyword={selectedKeyword}
            onClose={() => setSelectedKeyword(null)}
            query={detailQuery}
          />
        )}
      </AnimatePresence>
    </Layout>
  );
}
