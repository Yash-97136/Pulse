import { motion } from "framer-motion";
import { BarChart2, LineChart, Radar } from "lucide-react";
import { formatNumber } from "../utils/number";

interface HeroStripProps {
  kpi: {
    totalPosts: number;
    activeKeywords: number;
    anomaliesToday: number;
  };
  activeWindowMinutes?: number;
}

const cards = [
  {
    title: "Total posts processed",
    icon: BarChart2,
    gradient: "from-cyan-400/40 via-cyan-300/30 to-transparent"
  },
  {
    title: "Active keywords",
    icon: Radar,
    gradient: "from-fuchsia-400/40 via-purple-400/30 to-transparent"
  },
  {
    title: "Anomalies detected today",
    icon: LineChart,
    gradient: "from-amber-400/40 via-orange-400/30 to-transparent"
  }
] as const;

export function HeroStrip({ kpi, activeWindowMinutes = 60 }: HeroStripProps) {
  const values = [kpi.totalPosts, kpi.activeKeywords, kpi.anomaliesToday];
  return (
    <motion.section
      className="grid grid-cols-1 gap-4 md:grid-cols-3"
      initial={{ opacity: 0, y: 30 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.6, ease: "easeOut" }}
    >
      {cards.map((card, idx) => {
        const Icon = card.icon;
        const value = values[idx] ?? 0;
        const title = idx === 1 ? `${card.title} (${activeWindowMinutes}m)` : card.title;
        return (
          <motion.div
            key={card.title}
            whileHover={{ y: -4 }}
            className="glass-panel relative overflow-hidden p-6"
          >
            <div className={`pointer-events-none absolute inset-0 bg-gradient-to-br ${card.gradient}`} />
            <div className="relative flex items-start justify-between">
              <div>
                <p className="text-sm uppercase tracking-widest text-slate-400">{title}</p>
                <p className="mt-4 text-3xl font-semibold text-white">{formatNumber(value)}</p>
              </div>
              <Icon className="h-8 w-8 text-white/70" />
            </div>
          </motion.div>
        );
      })}
    </motion.section>
  );
}
