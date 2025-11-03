import * as Dialog from "@radix-ui/react-dialog";
import { motion } from "framer-motion";
import { X } from "lucide-react";
import dayjs from "../lib/dayjs";
import type { KeywordDetailResponse } from "../types";
import { formatNumber, formatPercent } from "../utils/number";
import { KeywordDetailChart } from "./KeywordDetailChart";

interface DetailDrawerProps {
  keyword: string;
  onClose: () => void;
  query: {
    data?: KeywordDetailResponse;
    isFetching: boolean;
    error: unknown;
  };
}

export function DetailDrawer({ keyword, onClose, query }: DetailDrawerProps) {
  const detail = query.data;
  return (
    <Dialog.Root open onOpenChange={(open) => !open && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-slate-950/60 backdrop-blur" />
        <Dialog.Content className="fixed bottom-0 left-0 right-0 z-50 mx-auto w-full max-w-5xl rounded-t-3xl border border-white/10 bg-slate-900/95 p-1">
          <motion.div
            initial={{ y: 80, opacity: 0 }}
            animate={{ y: 0, opacity: 1 }}
            exit={{ y: 80, opacity: 0 }}
            transition={{ type: "spring", damping: 22, stiffness: 180 }}
            className="glass-panel max-h-[85vh] overflow-y-auto p-8"
          >
            <div className="flex items-start justify-between">
              <div>
                <Dialog.Title className="text-2xl font-semibold text-white">{keyword}</Dialog.Title>
                {detail?.description && <p className="mt-2 text-sm text-slate-400">{detail.description}</p>}
                <p className="mt-3 text-xs uppercase tracking-[0.35em] text-slate-500">
                  updated {detail ? dayjs(detail.trendSeries.at(-1)?.timestamp).fromNow() : "just now"}
                </p>
              </div>
              <Dialog.Close asChild>
                <button className="rounded-full border border-white/10 p-2 text-slate-300 transition hover:border-white/30 hover:text-white">
                  <X className="h-4 w-4" />
                </button>
              </Dialog.Close>
            </div>

            <div className="mt-8 grid gap-6 lg:grid-cols-[2fr_1fr]">
              <div className="rounded-3xl border border-white/10 bg-white/5 p-6">
                <KeywordDetailChart series={detail?.trendSeries ?? []} />
              </div>
              <div className="space-y-4">
                <MetricCard label="Current score" value={detail ? formatNumber(detail.analytics.currentScore) : "—"} />
                <MetricCard label="Percentile" value={detail ? formatPercent(detail.analytics.percentile) : "—"} />
                <MetricCard
                  label="Document frequency"
                  value={detail ? formatNumber(detail.analytics.docFrequency) : "—"}
                />
                <MetricCard label="Velocity" value={detail ? formatNumber(detail.analytics.velocity) : "—"} />
              </div>
            </div>

            <div className="mt-10">
              <h3 className="text-sm font-semibold uppercase tracking-[0.3em] text-slate-400">Related posts</h3>
              <div className="mt-4 space-y-3">
                {detail?.relatedPosts.map((post) => (
                  <article key={post.id} className="rounded-2xl border border-white/10 bg-white/5 p-4">
                    <p className="text-sm text-slate-200">{post.text}</p>
                    <div className="mt-3 flex items-center justify-between text-xs text-slate-500">
                      <span>{post.source}</span>
                      <span>{dayjs(post.timestamp).fromNow()}</span>
                    </div>
                  </article>
                ))}
                {!detail?.relatedPosts?.length && <p className="text-sm text-slate-400">No posts in this window.</p>}
              </div>
            </div>
          </motion.div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function MetricCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-2xl border border-white/10 bg-white/5 p-4">
      <p className="text-xs uppercase tracking-[0.3em] text-slate-500">{label}</p>
      <p className="mt-2 text-xl font-semibold text-white">{value}</p>
    </div>
  );
}
