import axios from "axios";
import type {
  AnomaliesResponse,
  KeywordDetailResponse,
  TrendsResponse,
  AnomalyEvent
} from "../types";

// In production, point to the externally routed API origin via env; default remains localhost for dev
const BASE_URL = process.env.TRENDS_API_URL || window.location.origin.replace(/\/$/, "");

const client = axios.create({
  baseURL: BASE_URL,
  timeout: 15_000
});

client.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response) {
      error.message = `${error.response.status}: ${error.response.statusText}`;
    }
    return Promise.reject(error);
  }
);

type TrendMetric = TrendsResponse["trends"][number];
type RawTrendsResponse = TrendsResponse | Array<Partial<TrendMetric> & { count?: number }>;

function buildSparkline(volume: number): number[] {
  if (!Number.isFinite(volume) || volume <= 0) {
    return [0, 0, 0, 0, 0];
  }
  const base = Math.max(1, Math.floor(volume / 5));
  return [
    base,
    Math.max(base, Math.floor(base * 1.2)),
    Math.max(base, Math.floor(base * 1.4)),
    Math.max(base, Math.floor(base * 1.6)),
    volume
  ];
}

function normaliseTrend(item: Partial<TrendMetric> & { count?: number }, index: number): TrendMetric {
  const keyword = item.keyword ?? `keyword-${index + 1}`;
  const volume = item.volume ?? item.count ?? 0;
  const sparkline = Array.isArray(item.sparkline) && item.sparkline.length > 0
    ? item.sparkline.map((value) => (typeof value === "number" ? value : Number(value) || 0))
    : buildSparkline(volume);
  const score = item.score ?? volume;
  const delta = item.delta ?? (sparkline.length > 1 ? sparkline[sparkline.length - 1] - sparkline[0] : 0);
  const trendWindowMinutes = item.trendWindowMinutes ?? 60;
  const lastSeenAt = item.lastSeenAt ?? new Date().toISOString();

  return {
    keyword,
    score,
    delta,
    volume,
    sparkline,
    trendWindowMinutes,
    lastSeenAt,
    sentiment: item.sentiment
  };
}

function normaliseTrendsResponse(raw: RawTrendsResponse): TrendsResponse {
  if (Array.isArray(raw)) {
    const trends = raw.map((item, index) => normaliseTrend(item, index));
    const totalPosts = trends.reduce((sum, trend) => sum + trend.volume, 0);
    return {
      trends,
      meta: {
        totalPosts,
        windowMinutes: 60,
        generatedAt: new Date().toISOString(),
        // No pagination info available for array form
        hasMore: trends.length > 0,
        nextOffset: trends.length,
        totalKeywords: undefined
      }
    };
  }

  const trends = Array.isArray(raw?.trends)
    ? raw.trends.map((item, index) => normaliseTrend(item, index))
    : [];
  const totalPosts = raw?.meta?.totalPosts ?? trends.reduce((sum, trend) => sum + trend.volume, 0);

  return {
    trends,
    meta: {
      totalPosts,
      windowMinutes: raw?.meta?.windowMinutes ?? 60,
      generatedAt: raw?.meta?.generatedAt ?? new Date().toISOString(),
      activeKeywords: raw?.meta?.activeKeywords,
      totalKeywords: raw?.meta?.totalKeywords,
      nextOffset: raw?.meta?.nextOffset,
      hasMore: raw?.meta?.hasMore
    }
  };
}

export interface TrendsQueryParams {
  offset?: number;
  limit?: number;
}

export async function fetchTrends(params: TrendsQueryParams = {}): Promise<TrendsResponse> {
  const { data } = await client.get<RawTrendsResponse>("/api/trends", {
    params: {
      offset: params.offset ?? 0,
      limit: params.limit ?? 60
    }
  });
  return normaliseTrendsResponse(data);
}

export interface AnomaliesQueryParams {
  page?: number;
  limit?: number;
  keyword?: string | null;
  minZ?: number | null;
  since?: string | null; // ISO-8601
}

export async function fetchAnomalies(params: AnomaliesQueryParams = {}): Promise<AnomaliesResponse> {
  const { data } = await client.get<AnomaliesResponse>("/api/anomalies", {
    params: {
      page: params.page ?? 0,
      limit: params.limit ?? 40,
      keyword: params.keyword ?? undefined,
      minZ: params.minZ ?? undefined,
      since: params.since ?? undefined
    }
  });
  return data;
}

export async function fetchKeywordDetail(keyword: string): Promise<KeywordDetailResponse> {
  const { data } = await client.get<KeywordDetailResponse>(`/api/trends/${encodeURIComponent(keyword)}`);
  return data;
}

export function createAnomalyEventSource(onEvent: (event: AnomalyEvent) => void, onError?: (err: Event) => void) {
  const url = new URL("/api/anomalies/stream", BASE_URL);
  const es = new EventSource(url.toString());
  es.addEventListener("anomaly", (e) => {
    try {
      const parsed = JSON.parse((e as MessageEvent).data) as AnomalyEvent;
      onEvent(parsed);
    } catch (err) {
      // ignore parse errors
    }
  });
  es.onerror = (ev) => {
    if (onError) onError(ev);
  };
  return es;
}
