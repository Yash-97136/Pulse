import { useEffect, useMemo, useRef, useState } from "react";
import type { AnomalyEvent } from "../types";
import { createAnomalyEventSource } from "../lib/api";

export function useAnomalyStream(initial: AnomalyEvent[] = [], opts?: { max?: number }) {
  const [live, setLive] = useState<AnomalyEvent[]>([]);
  const maxItems = opts?.max ?? 100;
  const ids = useRef<Set<string>>(new Set(initial.map((a) => a.id)));

  useEffect(() => {
    // reset IDs if initial changes significantly
    ids.current = new Set(initial.map((a) => a.id));
  }, [initial]);

  useEffect(() => {
    const es = createAnomalyEventSource((event) => {
      if (!event?.id) return;
      if (ids.current.has(event.id)) return;
      ids.current.add(event.id);
      setLive((curr) => {
        const next = [event, ...curr];
        return next.slice(0, maxItems);
      });
    });

    return () => {
      try { es.close(); } catch {}
    };
  }, [maxItems]);

  const merged = useMemo(() => {
    const byId = new Map<string, AnomalyEvent>();
    for (const a of [...live, ...initial]) {
      byId.set(a.id, a);
    }
    const out = Array.from(byId.values()).sort((a, b) => (
      new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
    ));
    return out.slice(0, maxItems);
  }, [initial, live, maxItems]);

  return { events: merged, liveOnly: live };
}
