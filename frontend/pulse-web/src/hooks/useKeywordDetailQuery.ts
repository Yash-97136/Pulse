import { useQuery, type UseQueryOptions } from "@tanstack/react-query";
import { fetchKeywordDetail } from "../lib/api";
import type { KeywordDetailResponse } from "../types";

export function useKeywordDetailQuery(
  keyword: string | null,
  options?: Pick<UseQueryOptions<KeywordDetailResponse, Error>, "enabled">
) {
  return useQuery<KeywordDetailResponse, Error>({
    queryKey: ["keyword-detail", keyword],
    enabled: Boolean(keyword) && options?.enabled !== false,
    queryFn: async () => {
      if (!keyword) {
        throw new Error("Keyword required");
      }
      return fetchKeywordDetail(keyword);
    },
    refetchOnMount: true,
    staleTime: 10_000
  });
}
