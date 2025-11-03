import { useQuery } from "@tanstack/react-query";
import { fetchTrends } from "../lib/api";
import type { TrendsResponse } from "../types";

const QUERY_KEY = ["trends", "global"] as const;

export function useTrendsQuery() {
  const queryFn = (): Promise<TrendsResponse> => fetchTrends({ offset: 0, limit: 60 });
  return useQuery<TrendsResponse, Error>({
    queryKey: QUERY_KEY,
    queryFn,
    gcTime: 60_000
  });
}
