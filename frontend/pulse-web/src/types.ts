export interface TrendMetric {
  keyword: string;
  score: number;
  delta: number;
  volume: number;
  sparkline: number[];
  trendWindowMinutes: number;
  lastSeenAt: string;
  sentiment?: number;
}

export interface TrendsResponse {
  trends: TrendMetric[];
  meta: {
    totalPosts?: number;
    windowMinutes?: number;
    generatedAt: string;
    activeKeywords?: number;
    totalKeywords?: number;
    nextOffset?: number;
    hasMore?: boolean;
  };
}

export interface AnomalyEvent {
  id: string;
  keyword: string;
  zScore: number;
  baselineVolume: number;
  currentVolume: number;
  createdAt: string;
}

export interface AnomaliesResponse {
  anomalies: AnomalyEvent[];
  meta: {
    anomaliesToday?: number;
    windowMinutes?: number;
  };
}

export interface KeywordDetailResponse {
  keyword: string;
  description?: string;
  trendSeries: Array<{
    timestamp: string;
    value: number;
  }>;
  relatedPosts: Array<{
    id: string;
    text: string;
    source: string;
    timestamp: string;
    link?: string;
  }>;
  analytics: {
    currentScore: number;
    percentile: number;
    docFrequency: number;
    velocity: number;
  };
}
