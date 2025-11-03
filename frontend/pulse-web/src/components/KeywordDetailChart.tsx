import { ResponsiveContainer, AreaChart, Area, Tooltip, XAxis, YAxis } from "recharts";
import type { KeywordDetailResponse } from "../types";
import dayjs from "../lib/dayjs";

interface KeywordDetailChartProps {
  series: KeywordDetailResponse["trendSeries"];
}

const tooltipStyle = {
  backgroundColor: "rgba(15,23,42,0.9)",
  borderRadius: "12px",
  border: "1px solid rgba(255,255,255,0.08)",
  padding: "12px 16px",
  color: "white"
} as const;

export function KeywordDetailChart({ series }: KeywordDetailChartProps) {
  if (!series.length) {
    return <div className="flex h-64 items-center justify-center text-sm text-slate-400">No data available.</div>;
  }

  const data = series.map((point) => ({
    ...point,
    timestampLabel: dayjs(point.timestamp).format("MMM D, HH:mm")
  }));

  return (
    <ResponsiveContainer width="100%" height={240}>
      <AreaChart data={data} margin={{ left: 0, right: 0, top: 20, bottom: 0 }}>
        <defs>
          <linearGradient id="detailGradient" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stopColor="rgba(34,211,238,0.45)" />
            <stop offset="100%" stopColor="rgba(15,23,42,0)" />
          </linearGradient>
        </defs>
        <XAxis dataKey="timestampLabel" stroke="rgba(148,163,184,0.35)" fontSize={12} tickLine={false} axisLine={false} minTickGap={32} />
        <YAxis stroke="rgba(148,163,184,0.25)" fontSize={12} tickLine={false} axisLine={false} width={48} />
        <Tooltip
          cursor={{ stroke: "rgba(34,211,238,0.35)", strokeWidth: 1, strokeDasharray: "5 5" }}
          contentStyle={tooltipStyle}
          labelFormatter={(label) => `Snapshot ${label}`}
          formatter={(value: number) => value.toLocaleString()}
        />
        <Area type="monotone" dataKey="value" stroke="#22d3ee" strokeWidth={2} fill="url(#detailGradient)" />
      </AreaChart>
    </ResponsiveContainer>
  );
}
