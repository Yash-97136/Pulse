import { useMemo, useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { Search } from "lucide-react";
import clsx from "clsx";
import type { TrendMetric } from "../types";
import { formatNumber, formatPercent } from "../utils/number";

interface TrendExplorerProps {
  trends: TrendMetric[];
  isLoading: boolean;
  selectedKeyword: string | null;
  onSelect: (keyword: string) => void;
  onLoadMore?: () => void;
  canLoadMore?: boolean;
  isLoadingMore?: boolean;
}

export function TrendExplorer({ trends, isLoading, selectedKeyword, onSelect, onLoadMore, canLoadMore, isLoadingMore }: TrendExplorerProps) {
  const [query, setQuery] = useState("");

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return trends;
    return trends.filter((trend) => trend.keyword.toLowerCase().includes(q));
  }, [trends, query]);

  return (
    <section className="glass-panel h-full p-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold text-white">Live trend explorer</h2>
          <p className="text-sm text-slate-400">Track token velocity across the social firehose.</p>
        </div>
        <div className="relative">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search keywords"
            className="rounded-full border border-white/10 bg-white/5 py-2 pl-10 pr-4 text-sm text-white outline-none transition focus:border-accent-cyan/60"
          />
        </div>
      </div>

      <div className="mt-6 grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        {isLoading && (
          <div className="col-span-full text-center text-sm text-slate-400">Loading trends…</div>
        )}
        <AnimatePresence>
          {filtered.map((trend, index) => (
            <motion.button
              layout
              key={trend.keyword}
              onClick={() => onSelect(trend.keyword)}
              whileHover={{ translateY: -4, scale: 1.01 }}
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95 }}
              transition={{ delay: index * 0.015 }}
              className={clsx(
                "glass-panel flex flex-col items-start gap-3 p-4 text-left transition",
                selectedKeyword === trend.keyword && "ring-2 ring-accent-cyan/70"
              )}
            >
              <div className="flex w-full items-center justify-between">
                <h3 className="text-lg font-semibold text-white">{trend.keyword}</h3>
                <span className={clsx("text-sm font-medium", trend.delta >= 0 ? "text-emerald-300" : "text-rose-300")}
                >
                  {formatPercent(trend.delta)}
                </span>
              </div>
              <div className="flex w-full items-end justify-between text-slate-300">
                <div>
                  <p className="text-3xl font-semibold text-white">{formatNumber(trend.score)}</p>
                  <p className="text-xs uppercase tracking-widest text-slate-500">
                    total posts ({trend.trendWindowMinutes}m)
                  </p>
                </div>
                <div className="text-right text-xs text-slate-400">
                  <p>{formatNumber(trend.volume)} posts</p>
                  <p>{trend.trendWindowMinutes} min window</p>
                </div>
              </div>
              <Sparkline values={trend.sparkline} delta={trend.delta} />
            </motion.button>
          ))}
        </AnimatePresence>
        {!isLoading && filtered.length === 0 && (
          <div className="col-span-full text-center text-sm text-slate-400">No matches found.</div>
        )}
        {onLoadMore && (
          <div className="col-span-full mt-2 flex justify-center">
            <button
              onClick={onLoadMore}
              disabled={!canLoadMore || isLoadingMore}
              className="rounded-full border border-white/10 bg-white/5 px-4 py-2 text-sm text-white disabled:opacity-50"
            >
              {isLoadingMore ? "Loading…" : canLoadMore ? "Load more" : "No more results"}
            </button>
          </div>
        )}
      </div>
    </section>
  );
}

function Sparkline({ values, delta }: { values: number[]; delta: number }) {
  if (!values?.length) {
    return <div className="h-16 w-full rounded-2xl bg-white/5" />;
  }
  const maxValue = Math.max(...values);
  const minValue = Math.min(...values);
  const range = Math.max(maxValue - minValue, 1);
  const points = values
    .map((value, index) => {
      const x = (index / (values.length - 1 || 1)) * 100;
      const y = 100 - ((value - minValue) / range) * 100;
      return `${x},${y}`;
    })
    .join(" ");

  return (
    <svg viewBox="0 0 100 100" className="h-16 w-full">
      <polyline
        fill="url(#spark-gradient)"
        stroke="none"
        strokeWidth="2"
        points={`0,100 ${points} 100,100`}
      />
      <polyline
        fill="none"
        stroke={delta >= 0 ? "#34d399" : "#fb7185"}
        strokeWidth="3"
        strokeLinejoin="round"
        strokeLinecap="round"
        points={points}
      />
      <defs>
        <linearGradient id="spark-gradient" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stopColor={delta >= 0 ? "rgba(52,211,153,0.45)" : "rgba(251,113,133,0.45)"} />
          <stop offset="100%" stopColor="rgba(15,23,42,0)" />
        </linearGradient>
      </defs>
    </svg>
  );
}
