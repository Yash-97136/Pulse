import type { PropsWithChildren } from "react";

export function Layout({ children }: PropsWithChildren) {
  return (
    <div className="min-h-screen bg-background bg-radial-grid text-slate-100">
      <div className="pointer-events-none fixed inset-0 -z-10 bg-[radial-gradient(circle_at_20%_20%,rgba(34,211,238,0.15),transparent_55%),radial-gradient(circle_at_80%_0%,rgba(232,121,249,0.1),transparent_55%)]" />
      <div className="flex min-h-screen flex-col">
        {children}
      </div>
    </div>
  );
}
