import { useQuery } from "@tanstack/react-query";
import { fetchAnomalies, type AnomaliesQueryParams } from "../lib/api";
import type { AnomaliesResponse } from "../types";

const BASE_QUERY_KEY = ["anomalies", "latest"] as const;

export function useAnomaliesQuery(params: AnomaliesQueryParams = {}) {
  const key = [...BASE_QUERY_KEY, {
    page: params.page ?? 0,
    limit: params.limit ?? 40,
    keyword: params.keyword ?? null,
    minZ: params.minZ ?? null,
    since: params.since ?? null
  }];
  return useQuery<AnomaliesResponse, Error>({
    queryKey: key,
    queryFn: () => fetchAnomalies(params),
    gcTime: 60_000,
    refetchInterval: 30_000
  });
}
