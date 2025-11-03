import { motion } from "framer-motion";
import { Activity, Loader2, Zap } from "lucide-react";
import dayjs from "../lib/dayjs";

interface HeaderProps {
  envLabel: string;
  isRefreshing: boolean;
  lastUpdated: number;
}

export function Header({ envLabel, isRefreshing, lastUpdated }: HeaderProps) {
  const formatted = lastUpdated ? dayjs(lastUpdated).fromNow() : "never";

  return (
    <header className="sticky top-0 z-40 border-b border-white/5 backdrop-blur-xl">
      <div className="mx-auto flex w-full max-w-7xl items-center justify-between px-4 py-5 sm:px-6 lg:px-8">
        <div className="flex items-center gap-3">
          <motion.div
            className="flex h-10 w-10 items-center justify-center rounded-2xl bg-gradient-to-br from-accent-cyan/80 to-accent-magenta/70 text-slate-950"
            animate={{ rotate: isRefreshing ? 360 : 0 }}
            transition={{ repeat: isRefreshing ? Infinity : 0, duration: 1.4, ease: "linear" }}
          >
            <Zap className="h-5 w-5" />
          </motion.div>
          <div>
            <p className="text-sm font-semibold uppercase tracking-[0.3em] text-slate-400">Pulse</p>
            <h1 className="text-2xl font-semibold text-white">Social Signal Radar</h1>
          </div>
        </div>
        <div className="flex items-center gap-4">
          {/* {envLabel?.trim() && (
            <span className="rounded-full border border-white/10 bg-white/5 px-3 py-1 text-sm font-medium text-slate-300">
              {envLabel}
            </span>
          )} */}
          <div className="flex items-center gap-2 text-sm text-slate-400">
            {isRefreshing ? <Loader2 className="h-4 w-4 animate-spin" /> : <Activity className="h-4 w-4 text-accent-cyan" />}
            <span>{isRefreshing ? "Refreshing…" : `Synced ${formatted}`}</span>
          </div>
        </div>
      </div>
    </header>
  );
}
