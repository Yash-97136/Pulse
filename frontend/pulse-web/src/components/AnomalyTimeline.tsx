import { Fragment } from "react";
import { motion } from "framer-motion";
import { AlertTriangle, ArrowUpRight, History } from "lucide-react";
import clsx from "clsx";
import type { AnomalyEvent } from "../types";
import dayjs from "../lib/dayjs";
import { formatNumber } from "../utils/number";

interface AnomalyTimelineProps {
  anomalies: AnomalyEvent[];
  isLoading: boolean;
  liveIds?: Set<string>;
}

export function AnomalyTimeline({ anomalies, isLoading, liveIds }: AnomalyTimelineProps) {
  return (
    <aside className="glass-panel h-full p-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold text-white">Recent anomalies</h2>
          <p className="text-sm text-slate-400">Z-score spikes requiring analyst attention.</p>
        </div>
        <History className="h-5 w-5 text-accent-cyan" />
      </div>

  <div className="mt-6 space-y-3">
        {isLoading && <TimelinePlaceholder />}
        {!isLoading && anomalies.length === 0 && (
          <p className="text-sm text-slate-400">No anomalies detected in the current window.</p>
        )}
        {anomalies.map((anomaly, index) => (
          <Fragment key={anomaly.id}>
            <motion.div
              className="relative overflow-hidden rounded-2xl border border-white/10 bg-white/5 p-4"
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: index * 0.05, ease: "easeOut" }}
            >
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <p className="text-base font-semibold text-white">{anomaly.keyword}</p>
                  <p className="text-xs uppercase tracking-[0.35em] text-slate-500">{dayjs(anomaly.createdAt).fromNow()}</p>
                  {liveIds?.has(anomaly.id) && (
                    <span className="rounded-full border border-emerald-500/40 bg-emerald-500/10 px-2 py-0.5 text-[10px] font-medium text-emerald-300">
                      LIVE
                    </span>
                  )}
                </div>
                <div className="flex items-center gap-2 text-sm">
                  <span className="rounded-full bg-emerald-500/10 px-3 py-1 text-emerald-200">{formatNumber(anomaly.currentVolume)} posts</span>
                  <ArrowUpRight className="h-4 w-4 text-accent-magenta" />
                </div>
              </div>
              <div className="mt-4 flex items-center justify-between text-sm text-slate-300">
                <div className="flex items-center gap-2">
                  <AlertTriangle className="h-4 w-4 text-amber-300" />
                  <span className="font-medium">Z-score {anomaly.zScore.toFixed(2)}</span>
                </div>
                <div className="text-right text-xs">
                  <p>Baseline {formatNumber(anomaly.baselineVolume)}</p>
                  <p>Window +{formatNumber(anomaly.currentVolume - anomaly.baselineVolume)}</p>
                </div>
              </div>
            </motion.div>
            {index !== anomalies.length - 1 && <div className="mx-auto h-4 w-px bg-gradient-to-b from-white/20 via-white/5 to-transparent" />}
          </Fragment>
        ))}
      </div>
    </aside>
  );
}

function TimelinePlaceholder() {
  return (
    <div className="space-y-3">
      {Array.from({ length: 4 }).map((_, idx) => (
        <motion.div
          key={idx}
          className="h-20 w-full rounded-2xl bg-white/5"
          animate={{ opacity: [0.4, 0.9, 0.4] }}
          transition={{ repeat: Infinity, duration: 1.6, delay: idx * 0.2 }}
        />
      ))}
    </div>
  );
}
