import { useMemo } from "react";

export interface AnomalyFiltersState {
  keyword: string;
  minZ: number | null;
  since: string | null; // ISO string
}

interface Props {
  value: AnomalyFiltersState;
  onChange: (next: AnomalyFiltersState) => void;
}

export function AnomalyFilters({ value, onChange }: Props) {
  const sinceOptions = useMemo(() => ([
    { label: "Any time", value: null },
    { label: "Today", value: new Date(new Date().toISOString().slice(0, 10) + "T00:00:00Z").toISOString() },
    { label: "Last 1h", value: new Date(Date.now() - 60 * 60 * 1000).toISOString() },
    { label: "Last 6h", value: new Date(Date.now() - 6 * 60 * 60 * 1000).toISOString() }
  ]), []);

  return (
    <div className="glass-panel flex items-center justify-between gap-4 p-4">
      <div className="flex items-center gap-3">
        <input
          className="rounded-full border border-white/10 bg-white/5 px-4 py-2 text-sm text-white outline-none"
          placeholder="Filter by keyword"
          value={value.keyword}
          onChange={(e) => onChange({ ...value, keyword: e.target.value })}
        />
        <div className="flex items-center gap-2 text-sm text-slate-300">
          <label className="text-slate-400">min Z</label>
          <input
            type="number"
            step="0.1"
            min={0}
            className="w-24 rounded-md border border-white/10 bg-white/5 px-3 py-2 text-sm text-white outline-none"
            value={value.minZ ?? ""}
            onChange={(e) => onChange({ ...value, minZ: e.target.value === "" ? null : Number(e.target.value) })}
          />
        </div>
      </div>
      <div className="flex items-center gap-2 text-sm text-slate-300">
        <label className="text-slate-400">Since</label>
        <select
          className="rounded-md border border-white/10 bg-white/5 px-3 py-2 text-sm text-white outline-none"
          value={value.since ?? ""}
          onChange={(e) => onChange({ ...value, since: e.target.value || null })}
        >
          {sinceOptions.map((opt) => (
            <option key={opt.label} value={opt.value ?? ""}>
              {opt.label}
            </option>
          ))}
        </select>
      </div>
    </div>
  );
}
